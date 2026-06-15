import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;

import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters.GlobalOptType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.PairwiseStitchingResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.algorithm.PairwiseStitchingParameters;
import net.preibisch.stitcher.algorithm.SpimDataFilteringAndGrouping;
import net.preibisch.stitcher.algorithm.globalopt.GlobalOptStitcher;
import net.preibisch.stitcher.plugin.Calculate_Pairwise_Shifts;

public class RunBigStitcherHeadless {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException(
                "Usage: RunBigStitcherHeadless <xml> [dsX dsY dsZ minR maxR maxShiftX maxShiftY maxShiftZ]");
        }

        URI xml = new File(args[0]).toURI();
        long[] ds = new long[] {
            args.length > 1 ? Long.parseLong(args[1]) : 2,
            args.length > 2 ? Long.parseLong(args[2]) : 2,
            args.length > 3 ? Long.parseLong(args[3]) : 1
        };
        double minR = args.length > 4 ? Double.parseDouble(args[4]) : 0.05;
        double maxR = args.length > 5 ? Double.parseDouble(args[5]) : 0.9999;
        double maxShiftX = args.length > 6 ? Double.parseDouble(args[6]) : 800.0;
        double maxShiftY = args.length > 7 ? Double.parseDouble(args[7]) : 800.0;
        double maxShiftZ = args.length > 8 ? Double.parseDouble(args[8]) : 0.0;

        XmlIoSpimData2 io = new XmlIoSpimData2();
        SpimData2 data = io.load(xml);
        ArrayList<ViewId> selectedViews = SpimData2.getAllViewIdsSorted(
            data,
            data.getSequenceDescription().getViewSetupsOrdered(),
            data.getSequenceDescription().getTimePoints().getTimePointsOrdered());

        SpimDataFilteringAndGrouping<SpimData2> grouping = defaultGrouping(data, selectedViews);
        PairwiseStitchingParameters pairwise = new PairwiseStitchingParameters();

        System.out.println("(" + new Date() + "): BigStitcher phase-correlation pairwise registration");
        System.out.println("Downsampling: " + ds[0] + "," + ds[1] + "," + ds[2]);
        if (!Calculate_Pairwise_Shifts.processPhaseCorrelation(data, grouping, pairwise, ds)) {
            throw new RuntimeException("Pairwise phase-correlation registration failed.");
        }

        int before = data.getStitchingResults().getPairwiseResults().size();
        filterPairwise(data, minR, maxR, maxShiftX, maxShiftY, maxShiftZ);
        int after = data.getStitchingResults().getPairwiseResults().size();
        System.out.println("Pairwise links before filter: " + before);
        System.out.println("Pairwise links after filter: " + after);

        ArrayList<Pair<Group<ViewId>, Group<ViewId>>> removedInconsistentPairs = new ArrayList<>();
        GlobalOptimizationParameters params = new GlobalOptimizationParameters(
            3.5,
            7.0,
            GlobalOptType.TWO_ROUND_ITERATIVE,
            true,
            false);

        System.out.println("(" + new Date() + "): BigStitcher global optimization");
        if (!GlobalOptStitcher.processGlobalOptimization(data, grouping, params, removedInconsistentPairs, true)) {
            throw new RuntimeException("Global optimization failed.");
        }
        GlobalOptStitcher.removeInconsistentLinks(
            removedInconsistentPairs,
            data.getStitchingResults().getPairwiseResults());

        if (!io.save(data, xml)) {
            throw new RuntimeException("Failed to save " + xml);
        }
        System.out.println("(" + new Date() + "): Saved " + xml);
        System.exit(0);
    }

    private static SpimDataFilteringAndGrouping<SpimData2> defaultGrouping(
        SpimData2 data,
        ArrayList<ViewId> selectedViews) {
        SpimDataFilteringAndGrouping<SpimData2> grouping = new SpimDataFilteringAndGrouping<>(data);
        grouping.addFilters(selectedViews.stream()
            .map(vid -> (BasicViewDescription<?>) data.getSequenceDescription().getViewDescription(vid))
            .collect(Collectors.toList()));
        grouping.addGroupingFactor(Channel.class);
        grouping.addGroupingFactor(Illumination.class);
        grouping.addComparisonAxis(Tile.class);
        grouping.addApplicationAxis(TimePoint.class);
        grouping.addApplicationAxis(Angle.class);
        return grouping;
    }

    private static void filterPairwise(
        SpimData2 data,
        double minR,
        double maxR,
        double maxShiftX,
        double maxShiftY,
        double maxShiftZ) {
        Iterator<Map.Entry<Pair<Group<ViewId>, Group<ViewId>>, PairwiseStitchingResult<ViewId>>> it =
            data.getStitchingResults().getPairwiseResults().entrySet().iterator();

        while (it.hasNext()) {
            PairwiseStitchingResult<ViewId> result = it.next().getValue();
            AffineGet transform = result.getTransform();
            double dx = Math.abs(transform.get(0, 3));
            double dy = Math.abs(transform.get(1, 3));
            double dz = Math.abs(transform.get(2, 3));
            if (result.r() < minR || result.r() > maxR || dx > maxShiftX || dy > maxShiftY || dz > maxShiftZ) {
                it.remove();
            }
        }
    }
}
