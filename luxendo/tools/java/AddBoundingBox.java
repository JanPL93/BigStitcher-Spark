import java.io.File;
import java.net.URI;

import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;

public class AddBoundingBox {
    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException(
                "Usage: AddBoundingBox <xml> <name> <minX> <minY> <minZ> <maxX> <maxY> <maxZ>");
        }

        URI xml = new File(args[0]).toURI();
        String name = args[1];
        int[] min = new int[] {
            Integer.parseInt(args[2]),
            Integer.parseInt(args[3]),
            Integer.parseInt(args[4])
        };
        int[] max = new int[] {
            Integer.parseInt(args[5]),
            Integer.parseInt(args[6]),
            Integer.parseInt(args[7])
        };

        XmlIoSpimData2 io = new XmlIoSpimData2();
        SpimData2 data = io.load(xml);

        data.getBoundingBoxes().getBoundingBoxes()
            .removeIf(box -> name.equals(box.getTitle()));
        data.getBoundingBoxes().addBoundingBox(new BoundingBox(name, min, max));

        if (!io.save(data, xml)) {
            throw new RuntimeException("Failed to save " + xml);
        }
    }
}
