import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;

public class CheckImageJCalibration {
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: CheckImageJCalibration <image.tif>");
        }
        ImagePlus image = IJ.openImage(args[0]);
        if (image == null) {
            throw new IllegalArgumentException("ImageJ could not open: " + args[0]);
        }
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
