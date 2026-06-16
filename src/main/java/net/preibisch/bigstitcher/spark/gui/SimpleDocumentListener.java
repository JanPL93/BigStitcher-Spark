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

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** A {@link DocumentListener} that runs the same action for any change. */
class SimpleDocumentListener implements DocumentListener
{
	private final Runnable action;

	SimpleDocumentListener( final Runnable action )
	{
		this.action = action;
	}

	@Override
	public void insertUpdate( final DocumentEvent e )
	{
		action.run();
	}

	@Override
	public void removeUpdate( final DocumentEvent e )
	{
		action.run();
	}

	@Override
	public void changedUpdate( final DocumentEvent e )
	{
		action.run();
	}
}
