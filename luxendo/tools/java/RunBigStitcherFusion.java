import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import ij.ImagePlus;
import ij.io.FileSaver;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.imageplus.ImagePlusImgFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;

public class RunBigStitcherFusion {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                "Usage: RunBigStitcherFusion <xml> <output.tif> <boundingBoxName> [downsampling]");
        }

        URI xml = new File(args[0]).toURI();
        String output = args[1];
        String boundingBoxName = args[2];
        double downsampling = args.length > 3 ? Double.parseDouble(args[3]) : 4.0;

        SpimData2 data = new XmlIoSpimData2().load(xml);
        BoundingBox box = data.getBoundingBoxes().getBoundingBoxes().stream()
            .filter(bb -> boundingBoxName.equals(bb.getTitle()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Bounding box not found: " + boundingBoxName));

        List<ViewId> viewIds = new ArrayList<>(data.getSequenceDescription().getViewDescriptions().values());
        List<ViewId> removed = SpimData2.filterMissingViews(data, viewIds);
        System.out.println("(" + new Date() + "): Removed missing views: " + removed.size());
        System.out.println("(" + new Date() + "): Classic BigStitcher/Fiji fusion, downsampling=" + downsampling);

        Interval downsampledBox = FusionTools.createDownsampledBoundingBox(box, downsampling).getA();
        HashMap<ViewId, AffineTransform3D> registrations = TransformVirtual.adjustAllTransforms(
            viewIds,
            data.getViewRegistrations().getViewRegistrations(),
            Double.NaN,
            downsampling);

        RandomAccessibleInterval<FloatType> virtual = FusionTools.fuseVirtual(
            data.getSequenceDescription().getImgLoader(),
            registrations,
            data.getSequenceDescription().getViewDescriptions(),
            viewIds,
            FusionType.AVG_BLEND,
            downsampledBox);

        RandomAccessibleInterval<FloatType> fused = FusionTools.copyImg(
            virtual,
            new ImagePlusImgFactory<>(new FloatType()),
            new FloatType(),
            null,
            true);

        ImagePlus imp = FusionTools.getImagePlusInstance(fused, false, "BigStitcher classic fused", 0, 65535, DisplayImage.service);
        boolean ok = new FileSaver(imp).saveAsTiff(output);
        if (!ok) {
            throw new RuntimeException("Failed to save " + output);
        }
        System.out.println("(" + new Date() + "): Saved " + output);
        System.exit(0);
    }
}
