import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;

public class SampleN5Blocks {
    private static final class Stats {
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
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: SampleN5Blocks <n5Path> <dataset> [gridX,gridY,gridZ ...]");
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

            List<long[]> positions = new ArrayList<>();
            if (args.length > 2) {
                for (int i = 2; i < args.length; i++) {
                    String[] pieces = args[i].split(",");
                    if (pieces.length != dims.length) {
                        throw new IllegalArgumentException("Grid position must be 3D: " + args[i]);
                    }
                    long[] pos = new long[dims.length];
                    for (int d = 0; d < dims.length; d++) pos[d] = Long.parseLong(pieces[d]);
                    positions.add(pos);
                }
            } else {
                positions.add(new long[] {0, 0, 0});
                positions.add(new long[] {grid[0] / 2, grid[1] / 2, grid[2] / 2});
                positions.add(new long[] {grid[0] - 1, grid[1] - 1, grid[2] - 1});
            }

            System.out.println("n5=" + n5Path);
            System.out.println("dataset=" + dataset);
            System.out.println("dimensions=" + Arrays.toString(dims));
            System.out.println("blockSize=" + Arrays.toString(blockSize));
            System.out.println("gridBlocks=" + Arrays.toString(grid));

            for (long[] pos : positions) {
                DataBlock<?> block = n5.readBlock(dataset, attrs, pos);
                Stats stats = new Stats();
                if (block != null) acceptData(block.getData(), stats);
                System.out.println(
                        "grid=" + Arrays.toString(pos)
                        + " values=" + stats.values
                        + " nonzero=" + stats.nonzero
                        + " min=" + (stats.values == 0 ? "NA" : stats.min)
                        + " max=" + (stats.values == 0 ? "NA" : stats.max));
            }
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
