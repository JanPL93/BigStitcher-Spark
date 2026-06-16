/*-
 * #%L
 * Spark-based parallel BigStitcher project.
 * %%
 * Copyright (C) 2021 - 2024 Developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.bigstitcher.spark.gui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.preibisch.bigstitcher.spark.ClearInterestPoints;
import net.preibisch.bigstitcher.spark.ClearRegistrations;
import net.preibisch.bigstitcher.spark.CreateFusionContainer;
import net.preibisch.bigstitcher.spark.IntensitySolver;
import net.preibisch.bigstitcher.spark.Solver;
import net.preibisch.bigstitcher.spark.SparkAffineFusion;
import net.preibisch.bigstitcher.spark.SparkDownsample;
import net.preibisch.bigstitcher.spark.SparkGeometricDescriptorMatching;
import net.preibisch.bigstitcher.spark.SparkIntensityMatching;
import net.preibisch.bigstitcher.spark.SparkInterestPointDetection;
import net.preibisch.bigstitcher.spark.SparkNonRigidFusion;
import net.preibisch.bigstitcher.spark.SparkPairwiseStitching;
import net.preibisch.bigstitcher.spark.SparkResaveN5;
import net.preibisch.bigstitcher.spark.SplitDatasets;
import net.preibisch.bigstitcher.spark.TransformPoints;

/**
 * Single source of truth mapping the user-facing command name (as used by the
 * {@code ./install} generated scripts) to its implementing class.
 *
 * The ordering and names here mirror the {@code install_command} list in the
 * {@code install} script and must be kept in sync with it.
 */
public class CommandRegistry
{
	private static final Map< String, Class< ? > > COMMANDS = createCommands();

	private static Map< String, Class< ? > > createCommands()
	{
		final LinkedHashMap< String, Class< ? > > map = new LinkedHashMap<>();

		// workflow tools
		map.put( "resave", SparkResaveN5.class );
		map.put( "detect-interestpoints", SparkInterestPointDetection.class );
		map.put( "match-interestpoints", SparkGeometricDescriptorMatching.class );
		map.put( "stitching", SparkPairwiseStitching.class );
		map.put( "solver", Solver.class );
		map.put( "match-intensities", SparkIntensityMatching.class );
		map.put( "solve-intensities", IntensitySolver.class );
		map.put( "create-fusion-container", CreateFusionContainer.class );
		map.put( "affine-fusion", SparkAffineFusion.class );
		map.put( "nonrigid-fusion", SparkNonRigidFusion.class );

		// utils
		map.put( "split-images", SplitDatasets.class );
		map.put( "downsample", SparkDownsample.class );
		map.put( "clear-interestpoints", ClearInterestPoints.class );
		map.put( "clear-registrations", ClearRegistrations.class );
		map.put( "transform-points", TransformPoints.class );

		return Collections.unmodifiableMap( map );
	}

	/** @return the ordered map of command name to implementing class. */
	public static Map< String, Class< ? > > commands()
	{
		return COMMANDS;
	}

	/** @return the implementing class for the given command name, or null. */
	public static Class< ? > commandClass( final String name )
	{
		return COMMANDS.get( name );
	}
}
