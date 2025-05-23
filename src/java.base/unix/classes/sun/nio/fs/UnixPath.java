/*
 * Copyright (c) 2008, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.fs;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.file.DirectoryStream;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Arrays;
import java.util.Objects;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.util.ArraysSupport;

import static sun.nio.fs.UnixConstants.*;
import static sun.nio.fs.UnixNativeDispatcher.*;

/**
 * Linux/Mac implementation of java.nio.file.Path
 */
class UnixPath implements Path {

    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    private final UnixFileSystem fs;

    // internal representation
    private final byte[] path;

    // String representation (created lazily, no need to be volatile)
    private String stringValue;

    // cached hashcode (created lazily, no need to be volatile)
    private int hash;

    // array of offsets of elements in path (created lazily)
    private volatile int[] offsets;

    UnixPath(UnixFileSystem fs, byte[] path) {
        this.fs = fs;
        this.path = path;
    }

    UnixPath(UnixFileSystem fs, String input) {
        // removes redundant slashes and checks for invalid characters
        this(fs, encode(fs, normalizeAndCheck(input)));
    }

    // package-private
    // removes redundant slashes and check input for invalid characters
    static String normalizeAndCheck(String input) {
        int n = input.length();
        char prevChar = 0;
        for (int i=0; i < n; i++) {
            char c = input.charAt(i);
            if ((c == '/') && (prevChar == '/'))
                return normalize(input, n, i - 1);
            checkNotNul(input, c);
            prevChar = c;
        }
        if (prevChar == '/' && n > 1) {
            return input.substring(0, n - 1);
        }
        return input;
    }

    private static void checkNotNul(String input, char c) {
        if (c == '\u0000')
            throw new InvalidPathException(input, "Nul character not allowed");
    }

    private static String normalize(String input, int len, int off) {
        if (len == 0)
            return input;
        int n = len;
        while ((n > 0) && (input.charAt(n - 1) == '/')) n--;
        if (n == 0)
            return "/";
        StringBuilder sb = new StringBuilder(input.length());
        if (off > 0)
            sb.append(input, 0, off);
        char prevChar = 0;
        for (int i=off; i < n; i++) {
            char c = input.charAt(i);
            if ((c == '/') && (prevChar == '/'))
                continue;
            checkNotNul(input, c);
            sb.append(c);
            prevChar = c;
        }
        return sb.toString();
    }

    // encodes the given path-string into a sequence of bytes
    private static byte[] encode(UnixFileSystem fs, String input) {
        input = fs.normalizeNativePath(input);
        try {
            return JLA.uncheckedGetBytesNoRepl(input, Util.jnuEncoding());
        } catch (CharacterCodingException cce) {
            throw new InvalidPathException(input,
                "Malformed input or input contains unmappable characters");
        }
    }

    // package-private
    byte[] asByteArray() {
        return path;
    }

    // use this path when making system/library calls
    byte[] getByteArrayForSysCalls() {
        // resolve against default directory if required (chdir allowed or
        // file system default directory is not working directory)
        if (getFileSystem().needToResolveAgainstDefaultDirectory()) {
            return resolve(getFileSystem().defaultDirectory(), path);
        } else {
            if (!isEmpty()) {
                return path;
            } else {
                // empty path case will access current directory
                byte[] here = { '.' };
                return here;
            }
        }
    }

    // use this message when throwing exceptions
    String getPathForExceptionMessage() {
        return toString();
    }

    // use this path for permission checks
    String getPathForPermissionCheck() {
        if (getFileSystem().needToResolveAgainstDefaultDirectory()) {
            return Util.toString(getByteArrayForSysCalls());
        } else {
            return toString();
        }
    }

    // Checks that the given file is a UnixPath
    static UnixPath toUnixPath(Path obj) {
        if (obj == null)
            throw new NullPointerException();
        if (!(obj instanceof UnixPath))
            throw new ProviderMismatchException();
        return (UnixPath)obj;
    }

    // create offset list if not already created
    private void initOffsets() {
        if (offsets == null) {
            int count, index;

            // count names
            count = 0;
            index = 0;
            if (isEmpty()) {
                // empty path has one name
                count = 1;
            } else {
                while (index < path.length) {
                    byte c = path[index++];
                    if (c != '/') {
                        count++;
                        while (index < path.length && path[index] != '/')
                            index++;
                    }
                }
            }

            // populate offsets
            int[] result = new int[count];
            count = 0;
            index = 0;
            while (index < path.length) {
                byte c = path[index];
                if (c == '/') {
                    index++;
                } else {
                    result[count++] = index++;
                    while (index < path.length && path[index] != '/')
                        index++;
                }
            }
            synchronized (this) {
                if (offsets == null)
                    offsets = result;
            }
        }
    }

    // returns {@code true} if this path is an empty path
    boolean isEmpty() {
        return path.length == 0;
    }

    // returns an empty path
    private UnixPath emptyPath() {
        return new UnixPath(getFileSystem(), new byte[0]);
    }


    // return true if this path has "." or ".."
    private boolean hasDotOrDotDot() {
        int n = getNameCount();
        for (int i=0; i<n; i++) {
            byte[] bytes = getName(i).path;
            if ((bytes.length == 1 && bytes[0] == '.'))
                return true;
            if ((bytes.length == 2 && bytes[0] == '.') && bytes[1] == '.') {
                return true;
            }
        }
        return false;
    }

    @Override
    public UnixFileSystem getFileSystem() {
        return fs;
    }

    @Override
    public UnixPath getRoot() {
        if (path.length > 0 && path[0] == '/') {
            return getFileSystem().rootDirectory();
        } else {
            return null;
        }
    }

    @Override
    public UnixPath getFileName() {
        initOffsets();

        int count = offsets.length;

        // no elements so no name
        if (count == 0)
            return null;

        // one name element and no root component
        if (count == 1 && path.length > 0 && path[0] != '/')
            return this;

        int lastOffset = offsets[count-1];
        int len = path.length - lastOffset;
        byte[] result = new byte[len];
        System.arraycopy(path, lastOffset, result, 0, len);
        return new UnixPath(getFileSystem(), result);
    }

    @Override
    public UnixPath getParent() {
        initOffsets();

        int count = offsets.length;
        if (count == 0) {
            // no elements so no parent
            return null;
        }
        int len = offsets[count-1] - 1;
        if (len <= 0) {
            // parent is root only (may be null)
            return getRoot();
        }
        byte[] result = new byte[len];
        System.arraycopy(path, 0, result, 0, len);
        return new UnixPath(getFileSystem(), result);
    }

    @Override
    public int getNameCount() {
        initOffsets();
        return offsets.length;
    }

    @Override
    public UnixPath getName(int index) {
        initOffsets();
        if (index < 0)
            throw new IllegalArgumentException();
        if (index >= offsets.length)
            throw new IllegalArgumentException();

        int begin = offsets[index];
        int len;
        if (index == (offsets.length-1)) {
            len = path.length - begin;
        } else {
            len = offsets[index+1] - begin - 1;
        }

        // construct result
        byte[] result = new byte[len];
        System.arraycopy(path, begin, result, 0, len);
        return new UnixPath(getFileSystem(), result);
    }

    @Override
    public UnixPath subpath(int beginIndex, int endIndex) {
        initOffsets();

        if (beginIndex < 0)
            throw new IllegalArgumentException();
        if (beginIndex >= offsets.length)
            throw new IllegalArgumentException();
        if (endIndex > offsets.length)
            throw new IllegalArgumentException();
        if (beginIndex >= endIndex) {
            throw new IllegalArgumentException();
        }

        // starting offset and length
        int begin = offsets[beginIndex];
        int len;
        if (endIndex == offsets.length) {
            len = path.length - begin;
        } else {
            len = offsets[endIndex] - begin - 1;
        }

        // construct result
        byte[] result = new byte[len];
        System.arraycopy(path, begin, result, 0, len);
        return new UnixPath(getFileSystem(), result);
    }

    @Override
    public boolean isAbsolute() {
        return (path.length > 0 && path[0] == '/');
    }

    // Resolve child against given base
    private static byte[] resolve(byte[] base, byte[] child) {
        int baseLength = base.length;
        int childLength = child.length;
        if (childLength == 0)
            return base;
        if (baseLength == 0 || child[0] == '/')
            return child;
        byte[] result;
        if (baseLength == 1 && base[0] == '/') {
            result = new byte[childLength + 1];
            result[0] = '/';
            System.arraycopy(child, 0, result, 1, childLength);
        } else {
            result = new byte[baseLength + 1 + childLength];
            System.arraycopy(base, 0, result, 0, baseLength);
            result[base.length] = '/';
            System.arraycopy(child, 0, result, baseLength+1, childLength);
        }
        return result;
    }

    @Override
    public UnixPath resolve(Path obj) {
        byte[] other = toUnixPath(obj).path;
        if (other.length > 0 && other[0] == '/')
            return ((UnixPath)obj);
        byte[] result = resolve(path, other);
        return new UnixPath(getFileSystem(), result);
    }

    UnixPath resolve(byte[] other) {
        return resolve(new UnixPath(getFileSystem(), other));
    }

   private static final byte[] resolve(byte[] base, byte[]... children) {
       // 'start' is either zero, indicating the base, or indicates which
       // child is that last one which is an absolute path
       int start = 0;
       int resultLength = base.length;

       // Locate the last child which is an absolute path and calculate
       // the total number of bytes in the resolved path
       final int count = children.length;
       if (count > 0) {
           for (int i = 0; i < count; i++) {
               byte[] b = children[i];
               if (b.length > 0) {
                   if (b[0] == '/') {
                       start = i + 1;
                       resultLength = b.length;
                   } else {
                       if (resultLength > 0)
                           resultLength++;
                       resultLength += b.length;
                   }
               }
           }
       }

       // If the base is not being superseded by a child which is an
       // absolute path, then if at least one child is non-empty and
       // the base consists only of a '/', then decrement resultLength to
       // account for an extra '/' added in the resultLength computation.
       if (start == 0 && resultLength > base.length && base.length == 1 && base[0] == '/')
           resultLength--;

       // Allocate the result array and return if empty.
       byte[] result = new byte[resultLength];
       if (result.length == 0)
           return result;

       // Prepend the base if it is non-empty and would not later be
       // overwritten by an absolute child
       int offset = 0;
       if (start == 0 && base.length > 0) {
           System.arraycopy(base, 0, result, 0, base.length);
           offset += base.length;
       }

       // Append children starting with the last one which is an
       // absolute path
       if (count > 0) {
           int idx = Math.max(0, start - 1);
           for (int i = idx; i < count; i++) {
               byte[] b = children[i];
               if (b.length > 0) {
                   if (offset > 0 && result[offset - 1] != '/')
                       result[offset++] = '/';
                   System.arraycopy(b, 0, result, offset, b.length);
                   offset += b.length;
               }
           }
       }

       return result;
   }

    @Override
    public UnixPath resolve(Path first, Path... more) {
        if (more.length == 0)
            return resolve(first);

        byte[][] children = new byte[1 + more.length][];
        children[0] = toUnixPath(first).path;
        for (int i = 0; i < more.length; i++)
            children[i + 1] = toUnixPath(more[i]).path;

        byte[] result = resolve(path, children);
        return new UnixPath(getFileSystem(), result);
    }

    @Override
    public UnixPath relativize(Path obj) {
        UnixPath child = toUnixPath(obj);
        if (child.equals(this))
            return emptyPath();

        // can only relativize paths of the same type
        if (this.isAbsolute() != child.isAbsolute())
            throw new IllegalArgumentException("'other' is different type of Path");

        // this path is the empty path
        if (this.isEmpty())
            return child;

        UnixPath base = this;
        if (base.hasDotOrDotDot() || child.hasDotOrDotDot()) {
            base = base.normalize();
            child = child.normalize();
        }

        int baseCount = base.getNameCount();
        int childCount = child.getNameCount();

        // skip matching names
        int n = Math.min(baseCount, childCount);
        int i = 0;
        while (i < n) {
            if (!base.getName(i).equals(child.getName(i)))
                break;
            i++;
        }

        // remaining elements in child
        UnixPath childRemaining;
        boolean isChildEmpty;
        if (i == childCount) {
            childRemaining = emptyPath();
            isChildEmpty = true;
        } else {
            childRemaining = child.subpath(i, childCount);
            isChildEmpty = childRemaining.isEmpty();
        }

        // matched all of base
        if (i == baseCount) {
            return childRemaining;
        }

        // the remainder of base cannot contain ".."
        UnixPath baseRemaining = base.subpath(i, baseCount);
        if (baseRemaining.hasDotOrDotDot()) {
            throw new IllegalArgumentException("Unable to compute relative "
                    + " path from " + this + " to " + obj);
        }
        if (baseRemaining.isEmpty())
            return childRemaining;

        // number of ".." needed
        int dotdots = baseRemaining.getNameCount();
        if (dotdots == 0) {
            return childRemaining;
        }

        // result is a  "../" for each remaining name in base followed by the
        // remaining names in child. If the remainder is the empty path
        // then we don't add the final trailing slash.
        int len = dotdots*3 + childRemaining.path.length;
        if (isChildEmpty) {
            assert childRemaining.isEmpty();
            len--;
        }
        byte[] result = new byte[len];
        int pos = 0;
        while (dotdots > 0) {
            result[pos++] = (byte)'.';
            result[pos++] = (byte)'.';
            if (isChildEmpty) {
                if (dotdots > 1) result[pos++] = (byte)'/';
            } else {
                result[pos++] = (byte)'/';
            }
            dotdots--;
        }
        System.arraycopy(childRemaining.path,0, result, pos,
                             childRemaining.path.length);
        return new UnixPath(getFileSystem(), result);
    }

    @Override
    public UnixPath normalize() {
        final int count = getNameCount();
        if (count == 0 || isEmpty())
            return this;

        boolean[] ignore = new boolean[count];      // true => ignore name
        int[] size = new int[count];                // length of name
        int remaining = count;                      // number of names remaining
        boolean hasDotDot = false;                  // has at least one ..
        boolean isAbsolute = isAbsolute();

        // first pass:
        //   1. compute length of names
        //   2. mark all occurrences of "." to ignore
        //   3. and look for any occurrences of ".."
        for (int i=0; i<count; i++) {
            int begin = offsets[i];
            int len;
            if (i == (offsets.length-1)) {
                len = path.length - begin;
            } else {
                len = offsets[i+1] - begin - 1;
            }
            size[i] = len;

            if (path[begin] == '.') {
                if (len == 1) {
                    ignore[i] = true;  // ignore  "."
                    remaining--;
                }
                else {
                    if (path[begin+1] == '.')   // ".." found
                        hasDotDot = true;
                }
            }
        }

        // multiple passes to eliminate all occurrences of name/..
        if (hasDotDot) {
            int prevRemaining;
            do {
                prevRemaining = remaining;
                int prevName = -1;
                for (int i=0; i<count; i++) {
                    if (ignore[i])
                        continue;

                    // not a ".."
                    if (size[i] != 2) {
                        prevName = i;
                        continue;
                    }

                    int begin = offsets[i];
                    if (path[begin] != '.' || path[begin+1] != '.') {
                        prevName = i;
                        continue;
                    }

                    // ".." found
                    if (prevName >= 0) {
                        // name/<ignored>/.. found so mark name and ".." to be
                        // ignored
                        ignore[prevName] = true;
                        ignore[i] = true;
                        remaining = remaining - 2;
                        prevName = -1;
                    } else {
                        // Case: /<ignored>/.. so mark ".." as ignored
                        if (isAbsolute) {
                            boolean hasPrevious = false;
                            for (int j=0; j<i; j++) {
                                if (!ignore[j]) {
                                    hasPrevious = true;
                                    break;
                                }
                            }
                            if (!hasPrevious) {
                                // all proceeding names are ignored
                                ignore[i] = true;
                                remaining--;
                            }
                        }
                    }
                }
            } while (prevRemaining > remaining);
        }

        // no redundant names
        if (remaining == count)
            return this;

        // corner case - all names removed
        if (remaining == 0) {
            return isAbsolute ? getFileSystem().rootDirectory() : emptyPath();
        }

        // compute length of result
        int len = remaining - 1;
        if (isAbsolute)
            len++;

        for (int i=0; i<count; i++) {
            if (!ignore[i])
                len += size[i];
        }
        byte[] result = new byte[len];

        // copy names into result
        int pos = 0;
        if (isAbsolute)
            result[pos++] = '/';
        for (int i=0; i<count; i++) {
            if (!ignore[i]) {
                System.arraycopy(path, offsets[i], result, pos, size[i]);
                pos += size[i];
                if (--remaining > 0) {
                    result[pos++] = '/';
                }
            }
        }
        return new UnixPath(getFileSystem(), result);
    }

    @Override
    public boolean startsWith(Path other) {
        if (!(Objects.requireNonNull(other) instanceof UnixPath))
            return false;
        UnixPath that = (UnixPath)other;

        // other path is longer
        if (that.path.length > path.length)
            return false;

        int thisOffsetCount = getNameCount();
        int thatOffsetCount = that.getNameCount();

        // other path has no name elements
        if (thatOffsetCount == 0 && this.isAbsolute()) {
            return that.isEmpty() ? false : true;
        }

        // given path has more elements that this path
        if (thatOffsetCount > thisOffsetCount)
            return false;

        // same number of elements so must be exact match
        if ((thatOffsetCount == thisOffsetCount) &&
            (path.length != that.path.length)) {
            return false;
        }

        // check offsets of elements match
        for (int i=0; i<thatOffsetCount; i++) {
            Integer o1 = offsets[i];
            Integer o2 = that.offsets[i];
            if (!o1.equals(o2))
                return false;
        }

        // offsets match so need to compare bytes
        int i=0;
        while (i < that.path.length) {
            if (this.path[i] != that.path[i])
                return false;
            i++;
        }

        // final check that match is on name boundary
        if (i < path.length && this.path[i] != '/')
            return false;

        return true;
    }

    @Override
    public boolean endsWith(Path other) {
        if (!(Objects.requireNonNull(other) instanceof UnixPath))
            return false;
        UnixPath that = (UnixPath)other;

        int thisLen = path.length;
        int thatLen = that.path.length;

        // other path is longer
        if (thatLen > thisLen)
            return false;

        // other path is the empty path
        if (thisLen > 0 && thatLen == 0)
            return false;

        // other path is absolute so this path must be absolute
        if (that.isAbsolute() && !this.isAbsolute())
            return false;

        int thisOffsetCount = getNameCount();
        int thatOffsetCount = that.getNameCount();

        // given path has more elements that this path
        if (thatOffsetCount > thisOffsetCount) {
            return false;
        } else {
            // same number of elements
            if (thatOffsetCount == thisOffsetCount) {
                if (thisOffsetCount == 0)
                    return true;
                int expectedLen = thisLen;
                if (this.isAbsolute() && !that.isAbsolute())
                    expectedLen--;
                if (thatLen != expectedLen)
                    return false;
            } else {
                // this path has more elements so given path must be relative
                if (that.isAbsolute())
                    return false;
            }
        }

        // compare bytes
        int thisPos = offsets[thisOffsetCount - thatOffsetCount];
        int thatPos = that.offsets[0];
        return Arrays.equals(this.path, thisPos, thisLen, that.path, thatPos, thatLen);
    }

    @Override
    public int compareTo(Path other) {
        return Arrays.compareUnsigned(path, ((UnixPath) other).path);
    }

    @Override
    public boolean equals(Object ob) {
        return ob instanceof UnixPath p && compareTo(p) == 0;
    }

    @Override
    public int hashCode() {
        // OK if two or more threads compute hash
        int h = hash;
        if (h == 0) {
            h = ArraysSupport.hashCodeOfUnsigned(path, 0, path.length, 0);
            hash = h;
        }
        return h;
    }

    @Override
    public String toString() {
        // OK if two or more threads create a String
        String stringValue = this.stringValue;
        if (stringValue == null) {
            this.stringValue = stringValue = fs.normalizeJavaPath(Util.toString(path));     // platform encoding
        }
        return stringValue;
    }

    // -- file operations --

    // package-private
    int openForAttributeAccess(boolean followLinks) throws UnixException {
        int flags = O_RDONLY;
        if (!followLinks) {
            if (O_NOFOLLOW == 0)
                throw new UnixException
                    ("NOFOLLOW_LINKS is not supported on this platform");
            flags |= O_NOFOLLOW;
        }
        return open(this, flags, 0);
    }

    @Override
    public UnixPath toAbsolutePath() {
        if (isAbsolute()) {
            return this;
        }
        // The path is relative so need to resolve against default directory
        return new UnixPath(getFileSystem(),
            resolve(getFileSystem().defaultDirectory(), path));
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        UnixPath absolute = toAbsolutePath();

        // if resolving links then use realpath
        if (Util.followLinks(options)) {
            try {
                byte[] rp = realpath(absolute);
                return new UnixPath(getFileSystem(), rp);
            } catch (UnixException x) {
                x.rethrowAsIOException(this);
            }
        }

        // if not resolving links then eliminate "." and also ".."
        // where the previous element is neither a link nor "..".
        // if there is a preceding "..", then it might have followed
        // a link or a link followed by a sequence of two or more "..".
        // if for example one has the path "link/../../file",
        // then if a preceding ".." were eliminated, then the result
        // would be "<root>/link/file" instead of the correct
        // "<root>/link/../../file".
        UnixPath result = fs.rootDirectory();
        boolean parentIsDotDot = false;
        for (int i = 0; i < absolute.getNameCount(); i++) {
            UnixPath element = absolute.getName(i);

            // eliminate "."
            if ((element.asByteArray().length == 1) &&
                (element.asByteArray()[0] == '.'))
                continue;

            // cannot eliminate ".." if previous element is a link or ".."
            if ((element.asByteArray().length == 2) &&
                (element.asByteArray()[0] == '.') &&
                (element.asByteArray()[1] == '.'))
            {
                UnixFileAttributes attrs = null;
                try {
                    attrs = UnixFileAttributes.get(result, false);
                } catch (UnixException x) {
                    x.rethrowAsIOException(result);
                }
                if (!attrs.isSymbolicLink() && !parentIsDotDot) {
                    result = result.getParent();
                    if (result == null) {
                        result = fs.rootDirectory();
                    }
                    continue;
                }
                parentIsDotDot = true;
            } else {
                parentIsDotDot = false;
            }
            result = result.resolve(element);
        }

        // check whether file exists (without following links)
        try {
            UnixFileAttributes.get(result, false);
        } catch (UnixException x) {
            x.rethrowAsIOException(result);
        }

        // Return if the file system is not both case insensitive and retentive
        if (!fs.isCaseInsensitiveAndPreserving())
            return result;

        UnixPath path = fs.rootDirectory();

        // Traverse the result obtained above from the root downward, leaving
        // any '..' elements intact, and replacing other elements with the
        // entry in the same directory which has an equal key
        for (int i = 0; i < result.getNameCount(); i++ ) {
            UnixPath element = result.getName(i);

            // If the element is "..", append it directly and continue
            if (element.toString().equals("..")) {
                path = path.resolve(element);
                continue;
            }

            // Derive full path to element and check readability
            UnixPath elementPath = path.resolve(element);

            // Obtain the file key of elementPath
            UnixFileAttributes attrs = null;
            try {
                attrs = UnixFileAttributes.get(elementPath, false);
            } catch (UnixException x) {
                x.rethrowAsIOException(result);
            }
            final UnixFileKey elementKey = attrs.fileKey();

            // Obtain the directory stream pointer. It will be closed by
            // UnixDirectoryStream::close.
            long dp = -1;
            try {
                dp = opendir(path);
            } catch (UnixException x) {
                x.rethrowAsIOException(path);
            }

            // Obtain the stream of entries in the directory corresponding
            // to the path constructed thus far, and extract the entry whose
            // key is equal to the key of the current element
            DirectoryStream.Filter<Path> filter = (p) -> { return true; };
            try (DirectoryStream<Path> entries = new UnixDirectoryStream(path, dp, filter)) {
                boolean found = false;
                for (Path entry : entries) {
                    UnixPath p = path.resolve(entry.getFileName());
                    UnixFileAttributes attributes = null;
                    try {
                        attributes = UnixFileAttributes.get(p, false);
                        UnixFileKey key = attributes.fileKey();
                        if (key.equals(elementKey)) {
                            path = path.resolve(entry);
                            found = true;
                            break;
                        }
                    } catch (UnixException ignore) {
                        continue;
                    }
                }

                // Fallback which should in theory never happen
                if (!found) {
                    path = path.resolve(element);
                }
            }
        }

        return path;
    }

    @Override
    public URI toUri() {
        return UnixUriUtils.toUri(this);
    }

    @Override
    public WatchKey register(WatchService watcher,
                             WatchEvent.Kind<?>[] events,
                             WatchEvent.Modifier... modifiers)
        throws IOException
    {
        if (watcher == null)
            throw new NullPointerException();
        if (!(watcher instanceof AbstractWatchService))
            throw new ProviderMismatchException();
        return ((AbstractWatchService)watcher).register(this, events, modifiers);
    }
}
