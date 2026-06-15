args = split(getArgument(), "|");
path = args[0];
out = "";
if (args.length > 1)
    out = args[1];
open(path);
getPixelSize(unit, pixelWidth, pixelHeight, voxelDepth);
text = "title=" + getTitle() + "\n"
    + "unit=" + unit + "\n"
    + "pixelWidth=" + pixelWidth + "\n"
    + "pixelHeight=" + pixelHeight + "\n"
    + "voxelDepth=" + voxelDepth + "\n"
    + "width=" + getWidth() + "\n"
    + "height=" + getHeight() + "\n"
    + "slices=" + nSlices + "\n";
if (out != "")
    File.saveString(text, out);
else
    print(text);
close();
