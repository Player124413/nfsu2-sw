package com.nfsu2x;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts user-selected Xbox XISO images without requiring a native utility.
 *
 * Xbox images use XDVDFS rather than ordinary ISO9660. A small ISO9660 reader
 * is also included for images made by tools that wrap the game files in a
 * conventional data ISO. The image is copied to app-private temporary storage
 * first because many Storage Access Framework providers expose a non-seekable
 * stream instead of a file descriptor.
 */
final class IsoExtractor {
    private static final int SECTOR = 2048;
    private static final byte[] XDVDFS_MAGIC =
            "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
    private static final int[] XDVDFS_BASES = {
            0x00000000, 0x0000FD90, 0x00030600, 0x0FD90000, 0x18300000
    };
    private static final int DIRECTORY = 0x10;

    interface Progress {
        void onProgress(int percent);
    }

    private static final class Entry {
        final String name;
        final long sector;
        final long size;
        final boolean directory;
        final String relativePath;

        Entry(String name, long sector, long size, boolean directory,
              String relativePath) {
            this.name = name;
            this.sector = sector;
            this.size = size;
            this.directory = directory;
            this.relativePath = relativePath;
        }
    }

    private interface ImageFormat {
        List<Entry> readDirectory(RandomAccessFile image, long sector, long size,
                                  String relativePath) throws Exception;

        byte[] readBytes(RandomAccessFile image, long sector, long size) throws Exception;
    }

    private static final class XdvdfsFormat implements ImageFormat {
        private final long base;

        XdvdfsFormat(long base) {
            this.base = base;
        }

        private long offset(long sector) {
            return base + sector * SECTOR;
        }

        @Override
        public List<Entry> readDirectory(RandomAccessFile image, long sector,
                                         long size, String relativePath) throws Exception {
            byte[] data = readAt(image, offset(sector), size);
            return parseXdvdfsDirectory(data, relativePath);
        }

        @Override
        public byte[] readBytes(RandomAccessFile image, long sector, long size)
                throws Exception {
            return readAt(image, offset(sector), size);
        }
    }

    private static final class Iso9660Format implements ImageFormat {
        @Override
        public List<Entry> readDirectory(RandomAccessFile image, long sector,
                                         long size, String relativePath) throws Exception {
            byte[] data = readAt(image, sector * SECTOR, size);
            return parseIso9660Directory(data, relativePath);
        }

        @Override
        public byte[] readBytes(RandomAccessFile image, long sector, long size)
                throws Exception {
            return readAt(image, sector * SECTOR, size);
        }
    }

    private static final class ImageInfo {
        final ImageFormat format;
        final long rootSector;
        final long rootSize;

        ImageInfo(ImageFormat format, long rootSector, long rootSize) {
            this.format = format;
            this.rootSector = rootSector;
            this.rootSize = rootSize;
        }
    }

    private IsoExtractor() {}

    static void extract(ContentResolver resolver, Uri source, File destination,
                        Progress progress) throws Exception {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs()))
            throw new IllegalStateException("cannot create temporary game directory");

        File imageFile = new File(parent, ".selected-image.tmp");
        try {
            copyUri(resolver, source, imageFile);
            extractLocal(imageFile, destination, progress);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            imageFile.delete();
        }
    }

    private static void copyUri(ContentResolver resolver, Uri source, File target)
            throws Exception {
        InputStream raw = resolver.openInputStream(source);
        if (raw == null)
            throw new IllegalStateException("cannot open selected image");
        try (InputStream input = new BufferedInputStream(raw);
             BufferedOutputStream output = new BufferedOutputStream(
                     new FileOutputStream(target))) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1)
                output.write(buffer, 0, count);
        }
    }

    private static void extractLocal(File imageFile, File destination,
                                     Progress progress) throws Exception {
        try (RandomAccessFile image = new RandomAccessFile(imageFile, "r")) {
            ImageInfo info = identify(image);
            List<Entry> files = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            collect(image, info.format, info.rootSector, info.rootSize, "",
                    files, visited);
            if (files.isEmpty())
                throw new IllegalStateException("the image contains no files");

            long total = 0;
            for (Entry entry : files)
                total = safeAdd(total, entry.size);
            long copied = 0;
            for (Entry entry : files) {
                File out = safeChild(destination, entry.relativePath);
                File outParent = out.getParentFile();
                if (outParent != null && !outParent.exists() && !outParent.mkdirs())
                    throw new IllegalStateException("cannot create " + outParent);
                File partial = new File(out.getPath() + ".partial");
                byte[] buffer = new byte[1024 * 1024];
                long remaining = entry.size;
                image.seek(dataOffset(info.format, entry.sector));
                try (BufferedOutputStream output = new BufferedOutputStream(
                        new FileOutputStream(partial))) {
                    while (remaining > 0) {
                        int want = (int) Math.min(buffer.length, remaining);
                        image.readFully(buffer, 0, want);
                        output.write(buffer, 0, want);
                        remaining -= want;
                        copied += want;
                        if (progress != null)
                            progress.onProgress(total == 0 ? 100
                                    : (int) Math.min(99, copied * 100 / total));
                    }
                }
                if (!partial.renameTo(out))
                    throw new IllegalStateException("cannot finish " + out.getName());
            }
            if (progress != null)
                progress.onProgress(100);
        }
    }

    private static void collect(RandomAccessFile image, ImageFormat format,
                                long sector, long size, String relativePath,
                                List<Entry> files, Set<String> visited) throws Exception {
        String key = sector + ":" + size;
        if (!visited.add(key))
            throw new IllegalStateException("directory cycle in image");
        if (visited.size() > 100000)
            throw new IllegalStateException("image contains too many directories");

        for (Entry entry : format.readDirectory(image, sector, size, relativePath)) {
            if (entry.directory) {
                collect(image, format, entry.sector, entry.size,
                        entry.relativePath, files, visited);
            } else {
                files.add(entry);
            }
        }
    }

    private static ImageInfo identify(RandomAccessFile image) throws Exception {
        for (int base : XDVDFS_BASES) {
            long descriptor = (long) base + 32L * SECTOR;
            if (descriptor + XDVDFS_MAGIC.length <= image.length()
                    && matches(image, descriptor, XDVDFS_MAGIC)) {
                byte[] block = readAt(image, descriptor, SECTOR);
                long rootSector = u32(block, 0x14);
                long rootSize = u32(block, 0x18);
                validateRange(image, (long) base + rootSector * SECTOR, rootSize);
                return new ImageInfo(new XdvdfsFormat(base), rootSector, rootSize);
            }
        }

        // Standard ISO9660 primary volume descriptor.
        long descriptor = 16L * SECTOR;
        if (descriptor + SECTOR <= image.length()) {
            byte[] block = readAt(image, descriptor, SECTOR);
            if ((block[0] & 0xff) == 1
                    && new String(block, 1, 5, StandardCharsets.US_ASCII).equals("CD001")) {
                int rootOffset = 156;
                int recordLength = block[rootOffset] & 0xff;
                if (recordLength >= 34) {
                    long rootSector = u32(block, rootOffset + 2);
                    long rootSize = u32(block, rootOffset + 10);
                    validateRange(image, rootSector * SECTOR, rootSize);
                    return new ImageInfo(new Iso9660Format(), rootSector, rootSize);
                }
            }
        }
        throw new IllegalStateException("not an Xbox XISO or ISO9660 image");
    }

    private static List<Entry> parseXdvdfsDirectory(byte[] data, String parent)
            throws Exception {
        List<Entry> result = new ArrayList<>();
        for (int sectorOffset = 0; sectorOffset < data.length; sectorOffset += SECTOR) {
            int end = Math.min(data.length, sectorOffset + SECTOR);
            int offset = sectorOffset;
            while (offset + 0x0e <= end) {
                int left = u16(data, offset);
                int right = u16(data, offset + 2);
                int nameLength = data[offset + 0x0d] & 0xff;
                if ((left == 0xffff && right == 0xffff) || nameLength == 0)
                    break;
                int nameEnd = offset + 0x0e + nameLength;
                if (nameEnd > end)
                    break;
                String name = new String(data, offset + 0x0e, nameLength,
                        StandardCharsets.US_ASCII);
                addEntry(result, name, u32(data, offset + 4), u32(data, offset + 8),
                        data[offset + 0x0c] & 0xff, parent);
                offset += (0x0e + nameLength + 3) & ~3;
            }
        }
        return result;
    }

    private static List<Entry> parseIso9660Directory(byte[] data, String parent)
            throws Exception {
        List<Entry> result = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            int recordLength = data[offset] & 0xff;
            if (recordLength == 0) {
                offset = ((offset / SECTOR) + 1) * SECTOR;
                continue;
            }
            if (recordLength < 34 || offset + recordLength > data.length)
                break;
            int nameLength = data[offset + 32] & 0xff;
            if (nameLength > 0 && offset + 33 + nameLength <= data.length) {
                String name = new String(data, offset + 33, nameLength,
                        StandardCharsets.US_ASCII);
                if (!"\0".equals(name) && !"\1".equals(name)) {
                    int semicolon = name.indexOf(';');
                    if (semicolon >= 0)
                        name = name.substring(0, semicolon);
                    while (name.endsWith("."))
                        name = name.substring(0, name.length() - 1);
                    addEntry(result, name, u32(data, offset + 2),
                            u32(data, offset + 10), data[offset + 25] & 0xff, parent);
                }
            }
            offset += recordLength;
        }
        return result;
    }

    private static void addEntry(List<Entry> result, String name, long sector,
                                 long size, int attributes, String parent)
            throws Exception {
        if (name == null || name.length() == 0 || ".".equals(name)
                || "..".equals(name) || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            return;
        String relative = parent.length() == 0 ? name : parent + "/" + name;
        result.add(new Entry(name, sector, size, (attributes & DIRECTORY) != 0,
                relative));
    }

    private static File safeChild(File root, String relative) throws Exception {
        File child = new File(root, relative);
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!child.getCanonicalPath().startsWith(rootPath))
            throw new IllegalStateException("unsafe path in image: " + relative);
        return child;
    }

    private static long dataOffset(ImageFormat format, long sector) {
        if (format instanceof XdvdfsFormat)
            return ((XdvdfsFormat) format).base + sector * SECTOR;
        return sector * SECTOR;
    }

    private static byte[] readAt(RandomAccessFile image, long offset, long size)
            throws Exception {
        if (size < 0 || size > Integer.MAX_VALUE)
            throw new IllegalStateException("invalid directory size in image");
        validateRange(image, offset, size);
        byte[] data = new byte[(int) size];
        image.seek(offset);
        image.readFully(data);
        return data;
    }

    private static boolean matches(RandomAccessFile image, long offset, byte[] value)
            throws Exception {
        byte[] actual = readAt(image, offset, value.length);
        for (int i = 0; i < value.length; i++)
            if (actual[i] != value[i])
                return false;
        return true;
    }

    private static void validateRange(RandomAccessFile image, long offset, long size)
            throws Exception {
        if (offset < 0 || size < 0 || offset > image.length()
                || size > image.length() - offset)
            throw new IllegalStateException("image is truncated or contains invalid offsets");
    }

    private static long safeAdd(long left, long right) throws Exception {
        if (right < 0 || left > Long.MAX_VALUE - right)
            throw new IllegalStateException("image is too large");
        return left + right;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }
}
