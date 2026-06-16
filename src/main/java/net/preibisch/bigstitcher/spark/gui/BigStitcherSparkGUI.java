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

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;

/**
 * Swing front-end for BigStitcher-Spark. Lets the user browse to a project XML,
 * fill in any command's arguments (defaults pre-populated via picocli
 * introspection), and queue multiple commands to run sequentially, each in its
 * own JVM subprocess, with live console output.
 */
public class BigStitcherSparkGUI extends JFrame
{
	private static final long serialVersionUID = 1L;

	private final JTextField xmlField = new JTextField( 40 );
	private final JSpinner memorySpinner = new JSpinner( new SpinnerNumberModel( defaultMemoryGb(), 1, 1024, 1 ) );
	private final JSpinner threadsSpinner =
			new JSpinner( new SpinnerNumberModel( Runtime.getRuntime().availableProcessors(), 1, 1024, 1 ) );
	private final JCheckBox continueOnError = new JCheckBox( "Continue queue on error", false );

	private final JList< String > commandList = new JList<>( CommandRegistry.commands().keySet().toArray( new String[ 0 ] ) );
	private final JPanel formContainer = new JPanel( new BorderLayout() );
	private final Map< String, TaskFormPanel > formCache = new LinkedHashMap<>();

	private final List< Task > queue = new ArrayList<>();
	private final QueueTableModel queueModel = new QueueTableModel();
	private final JTable queueTable = new JTable( queueModel );
	private final JTextArea logArea = new JTextArea();

	private final JButton addButton = new JButton( "Add to queue ↓" );
	private final JButton runButton = new JButton( "Run queue" );
	private final JButton stopButton = new JButton( "Stop" );
	private final JButton removeButton = new JButton( "Remove" );
	private final JButton upButton = new JButton( "↑" );
	private final JButton downButton = new JButton( "↓" );

	private TaskRunner runner;
	private boolean syncingXml;

	public BigStitcherSparkGUI()
	{
		super( "BigStitcher-Spark" );
		setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );
		setLayout( new BorderLayout() );

		add( buildTopBar(), BorderLayout.NORTH );
		add( buildCenter(), BorderLayout.CENTER );

		wireEvents();

		commandList.setSelectedIndex( 0 );
		stopButton.setEnabled( false );

		setPreferredSize( new Dimension( 1100, 800 ) );
		pack();
		setLocationRelativeTo( null );
	}

	private JComponent buildTopBar()
	{
		final JPanel bar = new JPanel( new FlowLayout( FlowLayout.LEFT, 8, 6 ) );
		bar.setBorder( BorderFactory.createTitledBorder( "Project & resources" ) );

		bar.add( new JLabel( "Project XML:" ) );
		bar.add( xmlField );
		final JButton browse = new JButton( "Browse…" );
		browse.addActionListener( e -> browseXml() );
		bar.add( browse );

		bar.add( Box.createHorizontalStrut( 16 ) );
		bar.add( new JLabel( "Memory (GB):" ) );
		( ( JSpinner.DefaultEditor ) memorySpinner.getEditor() ).getTextField().setColumns( 4 );
		bar.add( memorySpinner );

		bar.add( new JLabel( "Threads (local[N]):" ) );
		( ( JSpinner.DefaultEditor ) threadsSpinner.getEditor() ).getTextField().setColumns( 4 );
		bar.add( threadsSpinner );

		bar.add( continueOnError );
		return bar;
	}

	private JComponent buildCenter()
	{
		// command selector + form
		final JScrollPane listScroll = new JScrollPane( commandList );
		listScroll.setBorder( BorderFactory.createTitledBorder( "Command" ) );
		listScroll.setPreferredSize( new Dimension( 230, 100 ) );

		formContainer.setBorder( BorderFactory.createTitledBorder( "Arguments" ) );
		final JPanel formWithButton = new JPanel( new BorderLayout() );
		formWithButton.add( new JScrollPane( formContainer ), BorderLayout.CENTER );
		final JPanel addBar = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );
		addBar.add( addButton );
		formWithButton.add( addBar, BorderLayout.SOUTH );

		final JSplitPane top = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT, listScroll, formWithButton );
		top.setResizeWeight( 0.2 );

		// queue + log
		queueTable.setSelectionMode( DefaultListSelectionModel.SINGLE_SELECTION );
		queueTable.getColumnModel().getColumn( 0 ).setPreferredWidth( 60 );
		queueTable.getColumnModel().getColumn( 1 ).setPreferredWidth( 150 );
		final JScrollPane queueScroll = new JScrollPane( queueTable );

		final JPanel queueControls = new JPanel( new FlowLayout( FlowLayout.LEFT ) );
		queueControls.add( runButton );
		queueControls.add( stopButton );
		queueControls.add( removeButton );
		queueControls.add( upButton );
		queueControls.add( downButton );

		final JPanel queuePanel = new JPanel( new BorderLayout() );
		queuePanel.setBorder( BorderFactory.createTitledBorder( "Task queue" ) );
		queuePanel.add( queueScroll, BorderLayout.CENTER );
		queuePanel.add( queueControls, BorderLayout.SOUTH );

		logArea.setEditable( false );
		logArea.setLineWrap( false );
		logArea.setFont( new java.awt.Font( java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12 ) );
		final JScrollPane logScroll = new JScrollPane( logArea );
		logScroll.setBorder( BorderFactory.createTitledBorder( "Output" ) );

		final JSplitPane bottom = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT, queuePanel, logScroll );
		bottom.setResizeWeight( 0.4 );

		final JSplitPane center = new JSplitPane( JSplitPane.VERTICAL_SPLIT, top, bottom );
		center.setResizeWeight( 0.55 );
		return center;
	}

	private void wireEvents()
	{
		commandList.addListSelectionListener( e -> {
			if ( !e.getValueIsAdjusting() )
				showForm( commandList.getSelectedValue() );
		} );

		// keep the shared XML field and the current form's XML field in sync
		xmlField.getDocument().addDocumentListener( new SimpleDocumentListener( () -> {
			if ( syncingXml )
				return;
			final TaskFormPanel form = currentForm();
			if ( form != null )
				form.setXmlPath( xmlField.getText() );
		} ) );

		addButton.addActionListener( e -> addToQueue() );
		runButton.addActionListener( e -> runQueue() );
		stopButton.addActionListener( e -> { if ( runner != null ) runner.stop(); } );
		removeButton.addActionListener( e -> removeSelected() );
		upButton.addActionListener( e -> move( -1 ) );
		downButton.addActionListener( e -> move( 1 ) );

		queueTable.getSelectionModel().addListSelectionListener( e -> {
			if ( !e.getValueIsAdjusting() )
				showSelectedTaskLog();
		} );
	}

	private void showForm( final String commandName )
	{
		if ( commandName == null )
			return;
		final TaskFormPanel form = formCache.computeIfAbsent( commandName, name -> {
			final TaskFormPanel panel = new TaskFormPanel( name, new ArgumentModel( CommandRegistry.commandClass( name ) ) );
			panel.setOnXmlChanged( path -> {
				if ( syncingXml )
					return;
				syncingXml = true;
				if ( !xmlField.getText().equals( path ) )
					xmlField.setText( path );
				syncingXml = false;
			} );
			return panel;
		} );
		form.setXmlPath( xmlField.getText() );

		formContainer.removeAll();
		formContainer.add( form, BorderLayout.NORTH );
		formContainer.revalidate();
		formContainer.repaint();
	}

	private TaskFormPanel currentForm()
	{
		return formCache.get( commandList.getSelectedValue() );
	}

	private void addToQueue()
	{
		final TaskFormPanel form = currentForm();
		if ( form == null )
			return;
		try
		{
			final List< String > args = form.buildArgs();
			final Task task = new Task( form.commandName(), CommandRegistry.commandClass( form.commandName() ), args );
			queue.add( task );
			queueModel.fireTableDataChanged();
		}
		catch ( final IllegalArgumentException ex )
		{
			JOptionPane.showMessageDialog( this, ex.getMessage(), "Missing arguments", JOptionPane.WARNING_MESSAGE );
		}
	}

	private void removeSelected()
	{
		final int row = queueTable.getSelectedRow();
		if ( row < 0 )
			return;
		final Task task = queue.get( row );
		if ( task.status() == Task.Status.RUNNING )
		{
			JOptionPane.showMessageDialog( this, "Cannot remove a running task.", "Busy", JOptionPane.WARNING_MESSAGE );
			return;
		}
		queue.remove( row );
		queueModel.fireTableDataChanged();
	}

	private void move( final int delta )
	{
		final int row = queueTable.getSelectedRow();
		final int target = row + delta;
		if ( row < 0 || target < 0 || target >= queue.size() )
			return;
		final Task task = queue.remove( row );
		queue.add( target, task );
		queueModel.fireTableDataChanged();
		queueTable.getSelectionModel().setSelectionInterval( target, target );
	}

	private void runQueue()
	{
		final List< Task > toRun = new ArrayList<>();
		for ( final Task task : queue )
			if ( task.status() == Task.Status.QUEUED )
				toRun.add( task );

		if ( toRun.isEmpty() )
		{
			JOptionPane.showMessageDialog( this, "No queued tasks to run.", "Empty queue", JOptionPane.INFORMATION_MESSAGE );
			return;
		}

		runButton.setEnabled( false );
		stopButton.setEnabled( true );

		final int memory = ( Integer ) memorySpinner.getValue();
		final int threads = ( Integer ) threadsSpinner.getValue();

		runner = new TaskRunner( memory, threads, continueOnError.isSelected(), new TaskRunner.Listener()
		{
			@Override
			public void onTaskStarted( final Task task )
			{
				SwingUtilities.invokeLater( () -> {
					selectTaskRow( task );
					refreshRow( task );
				} );
			}

			@Override
			public void onLogLine( final Task task, final String line )
			{
				SwingUtilities.invokeLater( () -> {
					if ( queueTable.getSelectedRow() == queue.indexOf( task ) )
					{
						logArea.append( line );
						logArea.setCaretPosition( logArea.getDocument().getLength() );
					}
				} );
			}

			@Override
			public void onTaskFinished( final Task task )
			{
				SwingUtilities.invokeLater( () -> refreshRow( task ) );
			}

			@Override
			public void onQueueFinished()
			{
				SwingUtilities.invokeLater( () -> {
					runButton.setEnabled( true );
					stopButton.setEnabled( false );
				} );
			}
		} );
		runner.start( toRun );
	}

	private void selectTaskRow( final Task task )
	{
		final int row = queue.indexOf( task );
		if ( row >= 0 )
		{
			queueTable.getSelectionModel().setSelectionInterval( row, row );
			logArea.setText( task.log() );
		}
	}

	private void refreshRow( final Task task )
	{
		final int row = queue.indexOf( task );
		if ( row >= 0 )
			queueModel.fireTableRowsUpdated( row, row );
	}

	private void showSelectedTaskLog()
	{
		final int row = queueTable.getSelectedRow();
		if ( row < 0 || row >= queue.size() )
			return;
		final Task task = queue.get( row );
		logArea.setText( task.log() );
		logArea.setCaretPosition( logArea.getDocument().getLength() );
	}

	private void browseXml()
	{
		final JFileChooser chooser = new JFileChooser();
		chooser.setFileFilter( new FileNameExtensionFilter( "BigStitcher project XML (*.xml)", "xml" ) );
		final String current = xmlField.getText().trim();
		if ( !current.isEmpty() && new File( current ).getParentFile() != null )
			chooser.setCurrentDirectory( new File( current ).getParentFile() );
		if ( chooser.showOpenDialog( this ) == JFileChooser.APPROVE_OPTION )
			xmlField.setText( chooser.getSelectedFile().getAbsolutePath() );
	}

	/** Queue table backed directly by the {@link #queue} list. */
	private class QueueTableModel extends AbstractTableModel
	{
		private static final long serialVersionUID = 1L;

		private final String[] columns = { "Command", "Status", "Arguments" };

		@Override
		public int getRowCount()
		{
			return queue.size();
		}

		@Override
		public int getColumnCount()
		{
			return columns.length;
		}

		@Override
		public String getColumnName( final int column )
		{
			return columns[ column ];
		}

		@Override
		public Object getValueAt( final int rowIndex, final int columnIndex )
		{
			final Task task = queue.get( rowIndex );
			switch ( columnIndex )
			{
			case 0:
				return task.commandName();
			case 1:
				return task.status();
			default:
				return String.join( " ", task.args() );
			}
		}
	}

	private static int defaultMemoryGb()
	{
		try
		{
			final java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
			for ( final String method : new String[] { "getTotalMemorySize", "getTotalPhysicalMemorySize" } )
			{
				try
				{
					final long bytes = ( Long ) os.getClass().getMethod( method ).invoke( os );
					final long gb = bytes / ( 1024L * 1024L * 1024L );
					return ( int ) Math.max( 1, gb * 4 / 5 ); // ~80%, like ./install
				}
				catch ( final NoSuchMethodException ignored )
				{
				}
			}
		}
		catch ( final Exception ignored )
		{
		}
		return 16;
	}

	public static void main( final String[] args )
	{
		try
		{
			UIManager.setLookAndFeel( UIManager.getSystemLookAndFeelClassName() );
		}
		catch ( final Exception ignored )
		{
		}
		SwingUtilities.invokeLater( () -> new BigStitcherSparkGUI().setVisible( true ) );
	}
}
