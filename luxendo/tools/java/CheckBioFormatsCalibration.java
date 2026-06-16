import ij.ImagePlus;
import ij.measure.Calibration;
import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;

public class CheckBioFormatsCalibration {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: CheckBioFormatsCalibration <image.tif>");
        }
        ImporterOptions options = new ImporterOptions();
        options.setId(args[0]);
        options.setQuiet(true);
        options.setVirtual(true);
        ImagePlus[] images = BF.openImagePlus(options);
        if (images == null || images.length == 0) {
            throw new IllegalArgumentException("Bio-Formats could not open: " + args[0]);
        }
        ImagePlus image = images[0];
        Calibration calibration = image.getCalibration();
        System.out.println("title=" + image.getTitle());
        System.out.println("width=" + image.getWidth());
        System.out.println("height=" + image.getHeight());
        System.out.println("slices=" + image.getNSlices());
        System.out.println("unit=" + calibration.getUnit());
        System.out.println("pixelWidth=" + calibration.pixelWidth);
        System.out.println("pixelHeight=" + calibration.pixelHeight);
        System.out.println("pixelDepth=" + calibration.pixelDepth);
    }
}
