import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;

public class ExportN5BigTiff {
    private static final int TIFF_ASCII = 2;
    private static final int TIFF_SHORT = 3;
    private static final int TIFF_LONG = 4;
    private static final int TIFF_RATIONAL = 5;
    private static final int TIFF_LONG8 = 16;
    private static final int BIGTIFF_HEADER_BYTES = 16;
    private static final int IFD_TAG_COUNT = 14;
    private static final int IFD_ENTRY_BYTES = 20;
    private static final int IFD_BYTES = 8 + IFD_TAG_COUNT * IFD_ENTRY_BYTES + 8;

    private static final class Args {
        String n5Path;
        String dataset = "setup0/timepoint0/s0";
        String outputPath;
        String compression = "Uncompressed";
        String voxelSize = "";
        String voxelUnit = "micrometer";
        int tileSize = 128;
        boolean overwrite = false;
        boolean patchExisting = false;
    }

    public static void main(String[] rawArgs) throws Exception {
        Args args = parseArgs(rawArgs);
        Path output = Paths.get(args.outputPath).toAbsolutePath();
        String compression = normalizeCompression(args.compression);

        if (args.patchExisting) {
            patchExistingBigTiff(output, args);
            writeSidecar(output, args, compression);
            System.out.println("patched=" + output);
            System.out.println("metadata=" + output + ".metadata.txt");
            return;
        }

        if (Files.exists(output)) {
            if (!args.overwrite) {
                throw new IllegalArgumentException("Output exists; pass --overwrite to replace it: " + output);
            }
            Files.delete(output);
        }
        Files.createDirectories(output.getParent());

        String n5Path = Paths.get(args.n5Path).toAbsolutePath().toString();

        try (N5FSReader n5 = new N5FSReader(n5Path)) {
            DatasetAttributes attrs = n5.getDatasetAttributes(args.dataset);
            if (attrs.getNumDimensions() != 3) {
                throw new IllegalArgumentException("Expected a 3D N5 dataset, got " + attrs.getNumDimensions() + "D");
            }
            if (attrs.getDataType() != DataType.UINT16) {
                throw new IllegalArgumentException("Expected UINT16 N5 data, got " + attrs.getDataType());
            }

            long[] dimsLong = attrs.getDimensions();
            int[] dims = checkedIntDimensions(dimsLong);
            int[] blockSize = attrs.getBlockSize();
            long[] grid = gridDimensions(dimsLong, blockSize);
            long planeBytes = checkedLongProduct(dims[0], dims[1], 2L, "plane byte count");
            byte[] imageDescription = imageJDescription(dims[2], args).getBytes(StandardCharsets.US_ASCII);
            long descriptionOffset = align8(BIGTIFF_HEADER_BYTES + (long) IFD_BYTES * dims[2]);
            long dataStart = align8(descriptionOffset + imageDescription.length);

            System.out.println("sourceN5=" + n5Path);
            System.out.println("dataset=" + args.dataset);
            System.out.println("output=" + output);
            System.out.println("dimensionsXYZ=" + Arrays.toString(dims));
            System.out.println("blockSizeXYZ=" + Arrays.toString(blockSize));
            System.out.println("gridBlocksXYZ=" + Arrays.toString(grid));
            System.out.println("compression=" + compression);
            System.out.println("writer=direct-uncompressed-bigtiff");
            System.out.println("planes=" + dims[2]);
            System.out.println("planeBytes=" + planeBytes);
            System.out.println("voxelSizeXYZ=" + formatVoxelSize(args));

            try (RandomAccessFile raf = new RandomAccessFile(output.toFile(), "rw");
                    FileChannel channel = raf.getChannel()) {
                writeHeaderAndIfds(
                        channel,
                        dims[0],
                        dims[1],
                        dims[2],
                        planeBytes,
                        dataStart,
                        descriptionOffset,
                        imageDescription,
                        args);
                channel.position(dataStart);
                exportPlanes(n5, args.dataset, attrs, dims, blockSize, grid, channel);
                channel.force(true);
            }
        }

        writeSidecar(output, args, compression);
        System.out.println("wrote=" + output);
        System.out.println("metadata=" + output + ".metadata.txt");
    }

    private static void exportPlanes(
            N5FSReader n5,
            String dataset,
            DatasetAttributes attrs,
            int[] dims,
            int[] blockSize,
            long[] grid,
            FileChannel channel) throws Exception {

        long blocksRead = 0;
        long planesWritten = 0;
        int planeBytes = checkedInt((long) dims[0] * dims[1] * 2L, "plane byte count");

        for (long gz = 0; gz < grid[2]; gz++) {
            DataBlock<?>[][] slab = new DataBlock<?>[(int) grid[1]][(int) grid[0]];
            for (long gy = 0; gy < grid[1]; gy++) {
                for (long gx = 0; gx < grid[0]; gx++) {
                    DataBlock<?> block = n5.readBlock(dataset, attrs, gx, gy, gz);
                    slab[(int) gy][(int) gx] = block;
                    if (block != null) {
                        blocksRead++;
                    }
                }
            }

            int z0 = checkedInt(gz * blockSize[2], "z block offset");
            int slabDepth = Math.min(blockSize[2], dims[2] - z0);
            for (int zLocal = 0; zLocal < slabDepth; zLocal++) {
                byte[] planeData = new byte[planeBytes];
                for (long gy = 0; gy < grid[1]; gy++) {
                    int y0 = checkedInt(gy * blockSize[1], "y block offset");
                    for (long gx = 0; gx < grid[0]; gx++) {
                        int x0 = checkedInt(gx * blockSize[0], "x block offset");
                        DataBlock<?> block = slab[(int) gy][(int) gx];
                        if (block != null) {
                            copyBlockSliceToPlane(block, zLocal, x0, y0, dims[0], dims[1], planeData);
                        }
                    }
                }
                writeFully(channel, ByteBuffer.wrap(planeData));
                planesWritten++;
            }

            System.out.println(
                    "completedZBlock=" + (gz + 1) + "/" + grid[2]
                            + " lastPlane=" + (z0 + slabDepth - 1)
                            + " blocksRead=" + blocksRead
                            + " planesWritten=" + planesWritten);
        }
    }

    private static void copyBlockSliceToPlane(
            DataBlock<?> block,
            int zLocal,
            int x0,
            int y0,
            int planeWidth,
            int planeHeight,
            byte[] planeData) {
        int[] size = block.getSize();
        if (zLocal >= size[2]) {
            return;
        }
        Object data = block.getData();
        int sx = size[0];
        int sy = size[1];
        int width = Math.min(sx, planeWidth - x0);
        int height = Math.min(sy, planeHeight - y0);
        for (int y = 0; y < height; y++) {
            int target = ((y0 + y) * planeWidth + x0) * 2;
            for (int x = 0; x < width; x++) {
                int sourceIndex = x + sx * (y + sy * zLocal);
                int value = unsigned16(data, sourceIndex);
                planeData[target++] = (byte) (value & 0xff);
                planeData[target++] = (byte) ((value >>> 8) & 0xff);
            }
        }
    }

    private static void writeHeaderAndIfds(
            FileChannel channel,
            int width,
            int height,
            int depth,
            long planeBytes,
            long dataStart,
            long descriptionOffset,
            byte[] imageDescription,
            Args args) throws Exception {
        ByteBuffer header = littleBuffer(BIGTIFF_HEADER_BYTES);
        header.put((byte) 'I');
        header.put((byte) 'I');
        header.putShort((short) 43);
        header.putShort((short) 8);
        header.putShort((short) 0);
        header.putLong(BIGTIFF_HEADER_BYTES);
        header.flip();
        writeFully(channel, header);

        for (int z = 0; z < depth; z++) {
            long ifdOffset = BIGTIFF_HEADER_BYTES + (long) z * IFD_BYTES;
            long nextIfdOffset = z + 1 < depth ? ifdOffset + IFD_BYTES : 0L;
            long stripOffset = dataStart + planeBytes * z;

            writeIfd(
                    channel,
                    width,
                    height,
                    stripOffset,
                    planeBytes,
                    descriptionOffset,
                    imageDescription.length,
                    nextIfdOffset,
                    args);
        }

        writePaddingTo(channel, descriptionOffset);
        writeFully(channel, ByteBuffer.wrap(imageDescription));
        writePaddingTo(channel, dataStart);
    }

    private static void writeIfd(
            FileChannel channel,
            int width,
            int height,
            long stripOffset,
            long planeBytes,
            long descriptionOffset,
            long imageDescriptionLength,
            long nextIfdOffset,
            Args args) throws Exception {
        long[] xResolution = resolutionRational(voxelSize(args)[0]);
        long[] yResolution = resolutionRational(voxelSize(args)[1]);

        ByteBuffer ifd = littleBuffer(IFD_BYTES);
        ifd.putLong(IFD_TAG_COUNT);
        putIfdEntry(ifd, 256, TIFF_LONG, 1, width);
        putIfdEntry(ifd, 257, TIFF_LONG, 1, height);
        putIfdEntry(ifd, 258, TIFF_SHORT, 1, 16);
        putIfdEntry(ifd, 259, TIFF_SHORT, 1, 1);
        putIfdEntry(ifd, 262, TIFF_SHORT, 1, 1);
        putIfdEntry(ifd, 270, TIFF_ASCII, imageDescriptionLength, descriptionOffset);
        putIfdEntry(ifd, 273, TIFF_LONG8, 1, stripOffset);
        putIfdEntry(ifd, 277, TIFF_SHORT, 1, 1);
        putIfdEntry(ifd, 278, TIFF_LONG, 1, height);
        putIfdEntry(ifd, 279, TIFF_LONG8, 1, planeBytes);
        putIfdRationalEntry(ifd, 282, xResolution[0], xResolution[1]);
        putIfdRationalEntry(ifd, 283, yResolution[0], yResolution[1]);
        putIfdEntry(ifd, 296, TIFF_SHORT, 1, 1);
        putIfdEntry(ifd, 339, TIFF_SHORT, 1, 1);
        ifd.putLong(nextIfdOffset);
        ifd.flip();
        writeFully(channel, ifd);
    }

    private static void putIfdEntry(ByteBuffer buffer, int tag, int type, long count, long value) {
        buffer.putShort((short) tag);
        buffer.putShort((short) type);
        buffer.putLong(count);
        buffer.putLong(value);
    }

    private static void putIfdRationalEntry(ByteBuffer buffer, int tag, long numerator, long denominator) {
        buffer.putShort((short) tag);
        buffer.putShort((short) TIFF_RATIONAL);
        buffer.putLong(1);
        buffer.putInt((int) numerator);
        buffer.putInt((int) denominator);
    }

    private static void writePaddingTo(FileChannel channel, long targetOffset) throws Exception {
        long padding = targetOffset - channel.position();
        if (padding > 0) {
            writeFully(channel, ByteBuffer.wrap(new byte[checkedInt(padding, "padding")]));
        }
    }

    private static ByteBuffer littleBuffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws Exception {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static int unsigned16(Object data, int index) {
        if (data instanceof short[]) {
            return ((short[]) data)[index] & 0xffff;
        }
        throw new IllegalArgumentException("Unsupported block data class for UINT16: " + data.getClass());
    }

    private static int[] checkedIntDimensions(long[] dims) {
        int[] out = new int[dims.length];
        for (int i = 0; i < dims.length; i++) {
            out[i] = checkedInt(dims[i], "dimension " + i);
        }
        return out;
    }

    private static int checkedInt(long value, String label) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " is outside Java int range: " + value);
        }
        return (int) value;
    }

    private static long checkedLongProduct(long a, long b, long c, String label) {
        long ab = Math.multiplyExact(a, b);
        return Math.multiplyExact(ab, c);
    }

    private static long[] gridDimensions(long[] dims, int[] blockSize) {
        long[] grid = new long[dims.length];
        for (int d = 0; d < dims.length; d++) {
            grid[d] = (dims[d] + blockSize[d] - 1) / blockSize[d];
        }
        return grid;
    }

    private static long align8(long value) {
        long remainder = value % 8;
        return remainder == 0 ? value : value + (8 - remainder);
    }

    private static String imageJDescription(int depth, Args args) {
        double[] voxel = voxelSize(args);
        return "ImageJ=1.54p\n"
                + "images=" + depth + "\n"
                + "slices=" + depth + "\n"
                + "mode=grayscale\n"
                + "unit=" + imageJUnit(args.voxelUnit) + "\n"
                + "spacing=" + Double.toString(voxel[2]) + "\n"
                + "loop=false\n"
                + "\0";
    }

    private static double[] voxelSize(Args args) {
        if (args.voxelSize == null || args.voxelSize.trim().length() == 0) {
            return new double[] {1.0, 1.0, 1.0};
        }
        String[] pieces = args.voxelSize.split(",");
        if (pieces.length != 3) {
            throw new IllegalArgumentException("--voxel-size must be x,y,z");
        }
        return new double[] {
                Double.parseDouble(pieces[0].trim()),
                Double.parseDouble(pieces[1].trim()),
                Double.parseDouble(pieces[2].trim())
        };
    }

    private static String formatVoxelSize(Args args) {
        double[] voxel = voxelSize(args);
        return voxel[0] + "," + voxel[1] + "," + voxel[2] + " " + imageJUnit(args.voxelUnit);
    }

    private static String imageJUnit(String unit) {
        String normalized = unit == null ? "" : unit.trim().toLowerCase();
        if (normalized.equals("micrometer")
                || normalized.equals("micrometers")
                || normalized.equals("micron")
                || normalized.equals("microns")
                || normalized.equals("um")
                || normalized.equals("µm")) {
            return "micron";
        }
        if (normalized.length() == 0) {
            return "pixel";
        }
        return normalized.replaceAll("[^A-Za-z0-9_.-]", "");
    }

    private static long[] resolutionRational(double pixelSize) {
        if (!(pixelSize > 0.0) || !Double.isFinite(pixelSize)) {
            return new long[] {1, 1};
        }
        double pixelsPerUnit = 1.0 / pixelSize;
        long denominator = 1_000_000_000L;
        long numerator = Math.round(pixelsPerUnit * denominator);
        while (numerator > 0xffffffffL && denominator > 1) {
            denominator /= 10L;
            numerator = Math.round(pixelsPerUnit * denominator);
        }
        if (numerator < 1) {
            numerator = 1;
        }
        return new long[] {numerator, denominator};
    }

    private static String normalizeCompression(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (normalized.length() == 0
                || normalized.equals("none")
                || normalized.equals("uncompressed")
                || normalized.equals("raw")) {
            return "Uncompressed";
        }
        throw new IllegalArgumentException(
                "The direct BigTIFF writer currently supports only uncompressed output.");
    }

    private static void patchExistingBigTiff(Path output, Args args) throws Exception {
        if (!Files.exists(output)) {
            throw new IllegalArgumentException("BigTIFF path does not exist: " + output);
        }

        try (RandomAccessFile raf = new RandomAccessFile(output.toFile(), "rw");
                FileChannel channel = raf.getChannel()) {
            BigTiffInfo info = readBigTiffInfo(channel);
            byte[] imageDescription = imageJDescription(info.depth, args).getBytes(StandardCharsets.US_ASCII);

            long newIfdStart = align8(channel.size());
            channel.position(channel.size());
            writePaddingTo(channel, newIfdStart);

            long descriptionOffset = newIfdStart + (long) IFD_BYTES * info.depth;
            for (int z = 0; z < info.depth; z++) {
                long nextIfdOffset = z + 1 < info.depth
                        ? newIfdStart + (long) (z + 1) * IFD_BYTES
                        : 0L;
                writeIfd(
                        channel,
                        info.width,
                        info.height,
                        info.stripOffsets.get(z),
                        info.stripByteCounts.get(z),
                        descriptionOffset,
                        imageDescription.length,
                        nextIfdOffset,
                        args);
            }
            writeFully(channel, ByteBuffer.wrap(imageDescription));
            channel.force(true);

            ByteBuffer firstIfdPointer = littleBuffer(8);
            firstIfdPointer.putLong(newIfdStart);
            firstIfdPointer.flip();
            channel.position(8);
            writeFully(channel, firstIfdPointer);
            channel.force(true);

            System.out.println("patchedImageJMetadata=true");
            System.out.println("pages=" + info.depth);
            System.out.println("dimensionsXYZ=[" + info.width + ", " + info.height + ", " + info.depth + "]");
            System.out.println("voxelSizeXYZ=" + formatVoxelSize(args));
        }
    }

    private static final class BigTiffInfo {
        int width;
        int height;
        int depth;
        List<Long> stripOffsets = new ArrayList<Long>();
        List<Long> stripByteCounts = new ArrayList<Long>();
    }

    private static BigTiffInfo readBigTiffInfo(FileChannel channel) throws Exception {
        ByteBuffer header = littleBuffer(BIGTIFF_HEADER_BYTES);
        channel.position(0);
        readFully(channel, header);
        header.flip();
        if (header.get() != 'I' || header.get() != 'I') {
            throw new IllegalArgumentException("Only little-endian BigTIFF files are supported");
        }
        int magic = header.getShort() & 0xffff;
        int offsetSize = header.getShort() & 0xffff;
        header.getShort();
        long ifdOffset = header.getLong();
        if (magic != 43 || offsetSize != 8) {
            throw new IllegalArgumentException("File is not a BigTIFF with 8-byte offsets");
        }

        BigTiffInfo info = new BigTiffInfo();
        while (ifdOffset != 0L) {
            channel.position(ifdOffset);
            ByteBuffer countBuffer = littleBuffer(8);
            readFully(channel, countBuffer);
            countBuffer.flip();
            long entryCount = countBuffer.getLong();

            int width = -1;
            int height = -1;
            Long stripOffset = null;
            Long stripByteCount = null;
            for (long i = 0; i < entryCount; i++) {
                ByteBuffer entry = littleBuffer(IFD_ENTRY_BYTES);
                readFully(channel, entry);
                entry.flip();
                int tag = entry.getShort() & 0xffff;
                int type = entry.getShort() & 0xffff;
                long count = entry.getLong();
                long value = entry.getLong();
                if (count != 1) {
                    continue;
                }
                if (tag == 256) {
                    width = checkedInt(inlineTiffValue(type, value), "ImageWidth");
                } else if (tag == 257) {
                    height = checkedInt(inlineTiffValue(type, value), "ImageLength");
                } else if (tag == 273) {
                    stripOffset = inlineTiffValue(type, value);
                } else if (tag == 279) {
                    stripByteCount = inlineTiffValue(type, value);
                }
            }

            ByteBuffer next = littleBuffer(8);
            readFully(channel, next);
            next.flip();
            ifdOffset = next.getLong();

            if (width <= 0 || height <= 0 || stripOffset == null || stripByteCount == null) {
                throw new IllegalArgumentException("Could not parse required BigTIFF tags");
            }
            if (info.depth == 0) {
                info.width = width;
                info.height = height;
            }
            info.stripOffsets.add(stripOffset);
            info.stripByteCounts.add(stripByteCount);
            info.depth++;
        }

        if (info.depth == 0) {
            throw new IllegalArgumentException("No IFDs found in BigTIFF");
        }
        return info;
    }

    private static long inlineTiffValue(int type, long value) {
        if (type == TIFF_SHORT) {
            return value & 0xffffL;
        }
        if (type == TIFF_LONG) {
            return value & 0xffffffffL;
        }
        if (type == TIFF_LONG8) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported inline TIFF type: " + type);
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws Exception {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IllegalArgumentException("Unexpected end of file while reading BigTIFF");
            }
        }
    }

    private static void writeSidecar(Path output, Args args, String compression) throws Exception {
        List<String> lines = new ArrayList<String>();
        if (args.n5Path != null) {
            lines.add("sourceN5=" + Paths.get(args.n5Path).toAbsolutePath());
        }
        lines.add("dataset=" + args.dataset);
        lines.add("outputBigTiff=" + output);
        lines.add("compression=" + compression);
        lines.add("voxelSizeXYZ=" + (args.voxelSize == null || args.voxelSize.length() == 0 ? "unknown" : args.voxelSize));
        lines.add("voxelUnit=" + args.voxelUnit);
        lines.add("bigTiff=true");
        lines.add("writer=direct-uncompressed-bigtiff");
        lines.add("imageJMetadata=true");
        lines.add("exportedAt=" + Instant.now().toString());
        Files.write(Paths.get(output.toString() + ".metadata.txt"), lines, StandardCharsets.UTF_8);
    }

    private static Args parseArgs(String[] rawArgs) {
        Args args = new Args();
        for (int i = 0; i < rawArgs.length; i++) {
            String key = rawArgs[i];
            if (key.equals("--overwrite")) {
                args.overwrite = true;
                continue;
            }
            if (key.equals("--patch-existing")) {
                args.patchExisting = true;
                continue;
            }

            if (i + 1 >= rawArgs.length) {
                throw new IllegalArgumentException("Missing value for " + key);
            }
            String value = rawArgs[++i];
            if (key.equals("--n5")) {
                args.n5Path = value;
            } else if (key.equals("--dataset")) {
                args.dataset = value;
            } else if (key.equals("--out")) {
                args.outputPath = value;
            } else if (key.equals("--compression")) {
                args.compression = value;
            } else if (key.equals("--voxel-size")) {
                args.voxelSize = value;
            } else if (key.equals("--voxel-unit")) {
                args.voxelUnit = value;
            } else if (key.equals("--tile-size")) {
                args.tileSize = Integer.parseInt(value);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + key);
            }
        }

        if (args.outputPath == null || (!args.patchExisting && args.n5Path == null)) {
            throw new IllegalArgumentException(
                    "Usage: ExportN5BigTiff --n5 <path.n5> [--dataset setup0/timepoint0/s0] "
                            + "--out <output.tif> [--compression Uncompressed] "
                            + "[--voxel-size x,y,z] [--voxel-unit micrometer] [--overwrite]\n"
                            + "   or: ExportN5BigTiff --patch-existing --out <output.tif> "
                            + "[--voxel-size x,y,z] [--voxel-unit micrometer]");
        }
        if (!args.patchExisting) {
            File n5 = new File(args.n5Path);
            if (!n5.exists()) {
                throw new IllegalArgumentException("N5 path does not exist: " + n5.getAbsolutePath());
            }
        }
        return args;
    }
}
