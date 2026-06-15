import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;

public class ExportN5MaxProjection {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: ExportN5MaxProjection <n5Path> <dataset> <outPng>");
        }

        String n5Path = Paths.get(args[0]).toAbsolutePath().toString();
        String dataset = args[1];
        File out = new File(args[2]);

        try (N5FSReader n5 = new N5FSReader(n5Path)) {
            DatasetAttributes attrs = n5.getDatasetAttributes(dataset);
            long[] dims = attrs.getDimensions();
            int[] blockSize = attrs.getBlockSize();
            if (dims.length != 3) {
                throw new IllegalArgumentException("Expected 3D dataset, got dimensions " + Arrays.toString(dims));
            }
            if (dims[0] > Integer.MAX_VALUE || dims[1] > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Projection too large for a PNG: " + Arrays.toString(dims));
            }

            int width = (int) dims[0];
            int height = (int) dims[1];
            int[] projection = new int[width * height];

            long[] grid = new long[dims.length];
            for (int d = 0; d < dims.length; d++) {
                grid[d] = (dims[d] + blockSize[d] - 1) / blockSize[d];
            }

            long[] pos = new long[dims.length];
            for (pos[2] = 0; pos[2] < grid[2]; pos[2]++) {
                for (pos[1] = 0; pos[1] < grid[1]; pos[1]++) {
                    for (pos[0] = 0; pos[0] < grid[0]; pos[0]++) {
                        DataBlock<?> block = n5.readBlock(dataset, attrs, pos);
                        if (block != null) {
                            addBlock(block, blockSize, dims, projection, width);
                        }
                    }
                }
            }

            int max = 0;
            for (int value : projection) {
                if (value > max) max = value;
            }
            if (max == 0) max = 1;

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int value = projection[x + width * y];
                    int gray = Math.min(255, Math.max(0, (int) Math.round(value * 255.0 / max)));
                    int rgb = (gray << 16) | (gray << 8) | gray;
                    image.setRGB(x, y, rgb);
                }
            }

            ImageIO.write(image, "png", out);
            System.out.println("wrote=" + out.getAbsolutePath());
            System.out.println("dimensions=" + Arrays.toString(dims));
            System.out.println("projectionMax=" + max);
        }
    }

    private static void addBlock(
            DataBlock<?> block,
            int[] nominalBlockSize,
            long[] dims,
            int[] projection,
            int width) {
        int[] size = block.getSize();
        long[] grid = block.getGridPosition();
        long gx0 = grid[0] * nominalBlockSize[0];
        long gy0 = grid[1] * nominalBlockSize[1];
        long gz0 = grid[2] * nominalBlockSize[2];
        Object data = block.getData();

        int sx = size[0];
        int sy = size[1];
        int sz = size[2];
        int index = 0;
        for (int z = 0; z < sz; z++) {
            long gz = gz0 + z;
            if (gz >= dims[2]) {
                index += sx * sy;
                continue;
            }
            for (int y = 0; y < sy; y++) {
                long gy = gy0 + y;
                if (gy >= dims[1]) {
                    index += sx;
                    continue;
                }
                for (int x = 0; x < sx; x++, index++) {
                    long gx = gx0 + x;
                    if (gx >= dims[0]) continue;
                    int value = unsignedValue(data, index);
                    int outIndex = (int) gx + width * (int) gy;
                    if (value > projection[outIndex]) projection[outIndex] = value;
                }
            }
        }
    }

    private static int unsignedValue(Object data, int index) {
        if (data instanceof byte[]) return ((byte[]) data)[index] & 0xff;
        if (data instanceof short[]) return ((short[]) data)[index] & 0xffff;
        if (data instanceof int[]) return ((int[]) data)[index];
        if (data instanceof float[]) return Math.round(((float[]) data)[index]);
        if (data instanceof double[]) return (int) Math.round(((double[]) data)[index]);
        throw new IllegalArgumentException("Unsupported N5 block data type: " + data.getClass());
    }
}
