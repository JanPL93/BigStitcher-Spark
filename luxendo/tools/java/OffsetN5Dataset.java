import java.nio.file.Paths;
import java.util.Arrays;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSWriter;

public class OffsetN5Dataset {
    private static final class Stats {
        long blocksRead = 0;
        long blocksWritten = 0;
        long values = 0;
        long clippedLow = 0;
        long clippedHigh = 0;
        long minBefore = Long.MAX_VALUE;
        long maxBefore = Long.MIN_VALUE;
        long minAfter = Long.MAX_VALUE;
        long maxAfter = Long.MIN_VALUE;

        void accept(long before, long after) {
            values++;
            if (before < minBefore) minBefore = before;
            if (before > maxBefore) maxBefore = before;
            if (after < minAfter) minAfter = after;
            if (after > maxAfter) maxAfter = after;
        }
    }

    public static void main(String[] args) throws Exception {
        String n5Path = null;
        String dataset = null;
        int offset = 0;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--n5":
                    n5Path = args[++i];
                    break;
                case "--dataset":
                    dataset = args[++i];
                    break;
                case "--offset":
                    offset = Integer.parseInt(args[++i]);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        if (n5Path == null || dataset == null) {
            throw new IllegalArgumentException("Usage: OffsetN5Dataset --n5 <n5Path> --dataset <dataset> --offset <signedInteger>");
        }

        String absoluteN5 = Paths.get(n5Path).toAbsolutePath().toString();
        try (N5FSWriter n5 = new N5FSWriter(absoluteN5)) {
            DatasetAttributes attrs = n5.getDatasetAttributes(dataset);
            long[] dims = attrs.getDimensions();
            int[] blockSize = attrs.getBlockSize();
            long[] grid = new long[dims.length];
            for (int d = 0; d < dims.length; d++) {
                grid[d] = (dims[d] + blockSize[d] - 1) / blockSize[d];
            }

            Stats stats = new Stats();
            long[] pos = new long[dims.length];
            scan(n5, dataset, attrs, grid, pos, 0, offset, stats);

            System.out.println("n5=" + absoluteN5);
            System.out.println("dataset=" + dataset);
            System.out.println("offset=" + offset);
            System.out.println("dimensions=" + Arrays.toString(dims));
            System.out.println("blockSize=" + Arrays.toString(blockSize));
            System.out.println("gridBlocks=" + Arrays.toString(grid));
            System.out.println("blocksRead=" + stats.blocksRead);
            System.out.println("blocksWritten=" + stats.blocksWritten);
            System.out.println("values=" + stats.values);
            System.out.println("clippedLow=" + stats.clippedLow);
            System.out.println("clippedHigh=" + stats.clippedHigh);
            System.out.println("minBefore=" + (stats.values == 0 ? "NA" : stats.minBefore));
            System.out.println("maxBefore=" + (stats.values == 0 ? "NA" : stats.maxBefore));
            System.out.println("minAfter=" + (stats.values == 0 ? "NA" : stats.minAfter));
            System.out.println("maxAfter=" + (stats.values == 0 ? "NA" : stats.maxAfter));
        }
    }

    private static void scan(
            N5FSWriter n5,
            String dataset,
            DatasetAttributes attrs,
            long[] grid,
            long[] pos,
            int d,
            int offset,
            Stats stats) throws Exception {
        if (d == pos.length) {
            DataBlock<?> block = n5.readBlock(dataset, attrs, pos);
            if (block != null) {
                stats.blocksRead++;
                applyOffset(block.getData(), offset, stats);
                n5.writeBlock(dataset, attrs, block);
                stats.blocksWritten++;
            }
            return;
        }

        for (long i = 0; i < grid[d]; i++) {
            pos[d] = i;
            scan(n5, dataset, attrs, grid, pos, d + 1, offset, stats);
        }
    }

    private static long clampToUnsigned16(long value, Stats stats) {
        if (value < 0) {
            stats.clippedLow++;
            return 0;
        }
        if (value > 65535) {
            stats.clippedHigh++;
            return 65535;
        }
        return value;
    }

    private static void applyOffset(Object data, int offset, Stats stats) {
        if (data instanceof short[]) {
            short[] values = (short[]) data;
            for (int i = 0; i < values.length; i++) {
                long before = values[i] & 0xffffL;
                long after = clampToUnsigned16(before + offset, stats);
                values[i] = (short) (after & 0xffff);
                stats.accept(before, after);
            }
        } else if (data instanceof byte[]) {
            byte[] values = (byte[]) data;
            for (int i = 0; i < values.length; i++) {
                long before = values[i] & 0xffL;
                long after = clampToUnsigned16(before + offset, stats);
                values[i] = (byte) (after & 0xff);
                stats.accept(before, after);
            }
        } else {
            throw new IllegalArgumentException("Unsupported N5 block data type: " + data.getClass());
        }
    }
}
