import java.nio.file.Paths;
import java.util.Arrays;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;

public class InspectN5Dataset {
    private static final class Stats {
        long blocks = 0;
        long values = 0;
        long nonzero = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;

        void acceptUnsigned(long value) {
            values++;
            if (value != 0) nonzero++;
            if (value < min) min = value;
            if (value > max) max = value;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: InspectN5Dataset <n5Path> <dataset>");
        }

        String n5Path = Paths.get(args[0]).toAbsolutePath().toString();
        String dataset = args[1];

        try (N5FSReader n5 = new N5FSReader(n5Path)) {
            DatasetAttributes attrs = n5.getDatasetAttributes(dataset);
            long[] dims = attrs.getDimensions();
            int[] blockSize = attrs.getBlockSize();
            long[] grid = new long[dims.length];
            for (int d = 0; d < dims.length; d++) {
                grid[d] = (dims[d] + blockSize[d] - 1) / blockSize[d];
            }

            Stats stats = new Stats();
            long[] pos = new long[dims.length];
            scan(n5, dataset, attrs, grid, pos, 0, stats);

            System.out.println("n5=" + n5Path);
            System.out.println("dataset=" + dataset);
            System.out.println("dimensions=" + Arrays.toString(dims));
            System.out.println("blockSize=" + Arrays.toString(blockSize));
            System.out.println("gridBlocks=" + Arrays.toString(grid));
            System.out.println("blocksRead=" + stats.blocks);
            System.out.println("values=" + stats.values);
            System.out.println("nonzero=" + stats.nonzero);
            System.out.println("min=" + (stats.values == 0 ? "NA" : stats.min));
            System.out.println("max=" + (stats.values == 0 ? "NA" : stats.max));
        }
    }

    private static void scan(
            N5FSReader n5,
            String dataset,
            DatasetAttributes attrs,
            long[] grid,
            long[] pos,
            int d,
            Stats stats) throws Exception {
        if (d == pos.length) {
            DataBlock<?> block = n5.readBlock(dataset, attrs, pos);
            if (block != null) {
                stats.blocks++;
                acceptData(block.getData(), stats);
            }
            return;
        }

        for (long i = 0; i < grid[d]; i++) {
            pos[d] = i;
            scan(n5, dataset, attrs, grid, pos, d + 1, stats);
        }
    }

    private static void acceptData(Object data, Stats stats) {
        if (data instanceof byte[]) {
            for (byte value : (byte[]) data) stats.acceptUnsigned(value & 0xffL);
        } else if (data instanceof short[]) {
            for (short value : (short[]) data) stats.acceptUnsigned(value & 0xffffL);
        } else if (data instanceof int[]) {
            for (int value : (int[]) data) stats.acceptUnsigned(value & 0xffffffffL);
        } else if (data instanceof long[]) {
            for (long value : (long[]) data) stats.acceptUnsigned(value);
        } else if (data instanceof float[]) {
            for (float value : (float[]) data) stats.acceptUnsigned(Math.round(value));
        } else if (data instanceof double[]) {
            for (double value : (double[]) data) stats.acceptUnsigned(Math.round(value));
        } else {
            throw new IllegalArgumentException("Unsupported N5 block data type: " + data.getClass());
        }
    }
}
