package util;

import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.IntStream;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import fiji.tool.SliceListener;
import fiji.tool.SliceObserver;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.gui.TextRoi;
import ij.measure.Calibration;
import ij.plugin.Animator;
import ij.plugin.PlugIn;
import ij.plugin.Zoom;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import net.imglib2.type.numeric.ARGBType;
import util.GUIStatePhase2.FeatureState;

@Plugin(type = Command.class, menuPath = "Plugins>Funke lab>Annotator ...")
public class MLTool implements Command, PlugIn
{
	// Done: default Mask: 0.5, red
	// Done: on start ask for root directory
	// Done: zoom to 300%
	// Done: play button (with FPS) > turn into 4D image
	// Done: support images: 
	// directory a,b,m: increasing ID, no leading zeros
	// Done: save in the parent directory
	// Done: first iteration a single text file

	final ForkJoinPool myPool = new ForkJoinPool( Runtime.getRuntime().availableProcessors() );

	volatile ForkJoinTask<?> task = null;

	ByteProcessor ip1, ip2, mask;
	ImagePlus mainImp;
	HashMap< Integer, ColorProcessor > main;
	ImageStack stack;

	Color color = setColor( Color.orange );
	float r, g, b;

	SliceObserver sliceObserver;
	String dir;
	ByteProcessor[] imgsA, imgsB, imgsM;

	public void setup( final String dir )
	{
		this.dir = dir;

		final File dirA = new File( dir, "A" );
		final File dirB = new File( dir, "B" );
		final File dirM = new File( dir, "M" );

		final List<String> filesA = Arrays.asList( dirA.list( (d,n) -> n.toLowerCase().endsWith( ".png" ) ) );
		final List<String> filesB = Arrays.asList( dirB.list( (d,n) -> n.toLowerCase().endsWith( ".png" ) ) );
		final List<String> filesM = Arrays.asList( dirM.list( (d,n) -> n.toLowerCase().endsWith( ".png" ) ) );

		if ( filesA == null || filesA.size() == 0 )
		{
			IJ.log( "No PNG's found in " + dirA );
			throw new RuntimeException( "No PNG's found in " + dirA );
		}

		if ( filesB == null || filesB.size() == 0 )
		{
			IJ.log( "No PNG's found in " + dirB );
			throw new RuntimeException( "No PNG's found in " + dirB );
		}

		if ( filesM == null || filesM.size() == 0 )
		{
			IJ.log( "No PNG's found in " + dirM );
			throw new RuntimeException( "No PNG's found in " + dirM );
		}

		if ( filesA.size() != filesB.size() || filesA.size() != filesM.size() )
		{
			IJ.log( "Amount of files is not equal..." );
			throw new RuntimeException( "Amount of files is not equal..." );
		}

		IJ.log( "found: " + filesA.size() + " pngs in A, " + filesB.size() + " pngs in B, "+ filesM.size() + " pngs in M; loading all ...");

		final long time = System.currentTimeMillis();

		final int numFiles = filesA.size();

		imgsA = new ByteProcessor[ numFiles ];
		imgsB = new ByteProcessor[ numFiles ];
		imgsM = new ByteProcessor[ numFiles ];

		IntStream.range( 0, numFiles ).parallel().forEach( i ->
		{
			final ImagePlus impA = new ImagePlus( new File( dirA.getAbsolutePath(), i + ".png" ).getAbsolutePath() );
			final ImagePlus impB = new ImagePlus( new File( dirB.getAbsolutePath(), i + ".png" ).getAbsolutePath() );
			final ImagePlus mask = new ImagePlus( new File( dirM.getAbsolutePath(), i + ".png" ).getAbsolutePath() );

			imgsA[ i ] = new ByteProcessor( impA.getWidth() + 32, impA.getHeight() );
			imgsB[ i ] = new ByteProcessor( impA.getWidth() + 32, impA.getHeight() );
			imgsM[ i ] = new ByteProcessor( impA.getWidth() + 32, impA.getHeight() );

			final ByteProcessor ipTmpA = (ByteProcessor)impA.getProcessor();
			final ByteProcessor ipTmpB = (ByteProcessor)impB.getProcessor();
			final ByteProcessor ipTmpM = (ByteProcessor)mask.getProcessor();

			for ( int y = 0; y < impA.getHeight(); ++y )
				for ( int x = 0; x < impA.getWidth(); ++x )
				{
					final int i1 = y * impA.getWidth() + x;
					final int i2 = y * imgsA[ i ].getWidth() + x;

					imgsA[ i ].set( i2, ipTmpA.get( i1 ) );
					imgsB[ i ].set( i2, ipTmpB.get( i1 ) );
					imgsM[ i ].set( i2, ipTmpM.get( i1 ) );
				}
		});

		IJ.log( "Done, took " + ( System.currentTimeMillis() - time ) + " ms." );

		setImages( imgsA[ 0 ], imgsB[ 0 ], imgsM[ 0 ] );
	}

	public void setImages( final ByteProcessor ip1, final ByteProcessor ip2, final ByteProcessor mask )
	{
		this.ip1 = ip1;
		this.ip2 = ip2;
		this.mask = mask;
	}

	public synchronized void interpolateMainImage( final GUIState state )
	{
		if ( task != null )
		{
			task.cancel( true );
			task = null;
		}

		try
		{
			task = myPool.submit(() ->
				main.entrySet().parallelStream().parallel().forEach( entry ->
					interpolateMask(ip1, ip2, (float)entry.getKey() / 100f, mask, (float)state.sliderMask.getValue() / 100f, r,g,b , entry.getValue())
				));//.get();
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}

		mainImp.updateAndDraw();
	}

	public Color setColor( final Color color )
	{
		if ( color == null )
			return null;

		this.color = color;

		this.r = 1.0f - color.getRed() / 255f;
		this.g = 1.0f - color.getGreen() / 255f;
		this.b = 1.0f - color.getBlue() / 255f;

		return color;
	}

	public static void interpolateMask( final ByteProcessor input1, final ByteProcessor input2, final float amount, final ByteProcessor mask, final float amountMask, final float amountR, final float amountG, final float amountB, final ColorProcessor target )
	{
		final int numPixels = target.getWidth() * target.getHeight();
		final float invAmount = 1.0f - amount;

		for ( int i = 0; i < numPixels; ++i )
		{
			final float v = input1.get( i ) * invAmount + input2.get( i ) * amount;
			final float maskedV = (mask.getf( i ) / 255.0f) * amountMask;
			final float tmp = v * maskedV;

			target.set( i, ARGBType.rgba(v - amountR*tmp, v- amountG*tmp, v- amountB*tmp, 255.0f) );
		}
	}

	public void showDialog(
			final int maxFrame,
			final double defaultMagnification,
			final int defaultMask,
			final Color defaultColor,
			final boolean loadExisting,
			final String json )
	{
		setColor( defaultColor );

		final boolean phase1 = (json == null || json.trim().length() == 0);
		final GUIState state = ( phase1 ? new GUIStatePhase1( this ) : new GUIStatePhase2( this ) );

		// create dialog
		state.dialog = new JDialog( (JFrame)null, state.labelDialog + "0", false);
		state.dialog.setLayout(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;

		// GRID Y=0
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 1*2;
		state.text1 = new JButton( "Image 0" );
		state.text1.setBorderPainted( false );
		state.dialog.add( state.text1, c );

		c.gridx = 1*2;
		c.gridy = 0;
		c.gridwidth = 2*2;
		state.sliderImg = new JSlider( 0, imgsA.length - 1 );
		state.sliderImg.setValue( 0 );
		state.sliderImg.addChangeListener( e -> state.setImage() );
		state.dialog.add( state.sliderImg, c );

		c.gridx = 3*2;
		c.gridy = 0;
		c.gridwidth = 1*2;
		state.text2 = new JButton( "Image " + ( imgsA.length - 1 ) );
		state.text2.setBorderPainted( false );
		state.dialog.add( state.text2, c );

		// GRID Y=1
		c.gridx = 0;
		c.gridy = 1;
		c.gridwidth = 1*2;
		state.text3 = new JButton( "Mask 0.0" );
		state.text3.setBorderPainted( false );
		state.text3.setForeground( color );
		state.text3.addActionListener( e ->
		{
			setColor( JColorChooser.showDialog( state.dialog, "Choose a color", color ) );
			state.text3.setForeground( color );
			state.text4.setForeground( color );
			interpolateMainImage( state );
		} );
		state.dialog.add( state.text3, c );

		c.gridx = 1*2;
		c.gridy = 1;
		c.gridwidth = 2*2;
		state.sliderMask = new JSlider( 0, 100 );
		state.sliderMask.setValue( defaultMask );
		state.sliderMask.addChangeListener( e -> interpolateMainImage( state ) );
		state.dialog.add( state.sliderMask, c );

		c.gridx = 3*2;
		c.gridy = 1;
		c.gridwidth = 1*2;
		state.text4 = new JButton( "Mask 1.0" );
		state.text4.setBorderPainted( false );
		state.text4.setForeground( color );
		state.text4.addActionListener( e ->
		{
			setColor( JColorChooser.showDialog(state. dialog, "Choose a color", color ) );
			state.text3.setForeground( color );
			state.text4.setForeground( color );
			interpolateMainImage( state );
		} );
		state.dialog.add( state.text4, c );

		// GRID Y=2
		c.gridx = 0;
		c.gridy = 2;
		c.gridwidth = 1*2;
		state.back = new JButton( "Prev. Img" );
		state.back.addActionListener( e -> state.prevImage() ) ;
		state.dialog.add( state.back, c );

		c.gridx = 1*2;
		c.gridy = 2;
		c.gridwidth = 1*2;
		state.forward = new JButton( "Next Img" );
		state.forward.addActionListener( e -> state.nextImage() ) ;
		state.dialog.add( state.forward, c );

		c.gridx = 2*2;
		c.gridy = 2;
		c.gridwidth = 1*2;
		state.save = new JButton( "Save" );
		state.save.addActionListener( e -> state.save( dir ) );
		state.dialog.add( state.save, c );

		c.gridx = 3*2;
		c.gridy = 2;
		c.gridwidth = 1*2;
		state.quit = new JButton( "Quit" );
		state.quit.addActionListener( e ->
		{
			final int choice = JOptionPane.showConfirmDialog( state.dialog,
					"Do you want to save before closing?",
					"Confirmation",
					JOptionPane.YES_NO_CANCEL_OPTION );
			
			if ( choice == JOptionPane.CANCEL_OPTION )
				return;
			else if ( choice == JOptionPane.YES_OPTION )
				if ( !state.save( dir ) )
					return;

			sliceObserver.unregister();
			mainImp.close();
			state.dialog.dispose();
		});
		state.dialog.add( state.quit, c );

		if ( phase1 )
		{
			final GUIStatePhase1 state1 = (GUIStatePhase1)state;

			// GRID Y=3
			c.fill = GridBagConstraints.HORIZONTAL;
			c.gridx = 0;
			c.gridy = 3;
			c.gridwidth = 4*2;
			c.gridheight = 2;
			c.ipady = 200;
			c.ipadx = 300;
			state1.textfield = new JTextArea();
			state.dialog.add( new JScrollPane(state1.textfield), c );
	
			// try loading an existing notes file
			if ( loadExisting )
				state1.load( dir );
		}
		else
		{
			final GUIStatePhase2 state2 = (GUIStatePhase2)state;

			if ( !state2.setup( imgsA.length, json ) )
				return;

			// try loading an existing CSV file with the state
			if ( loadExisting )
				state2.load( dir );

			final JPopupMenu popupMenu3 = new JPopupMenu();
			final JMenuItem item3 = new JMenuItem( "Next image without annotations" );
			final JMenuItem item4 = new JMenuItem( "Next image marked as invalid" );
			item3.addActionListener( e -> state2.nextImageWithFeature( FeatureState.NOT_ASSIGNED ) );
			item4.addActionListener( e -> state2.nextImageWithFeature( FeatureState.INVALID ) );
			popupMenu3.add( item3 );
			popupMenu3.add( item4 );
			state2.forward.setComponentPopupMenu( popupMenu3 );

			final JPopupMenu popupMenu4 = new JPopupMenu();
			final JMenuItem item5 = new JMenuItem( "Previous image without annotations" );
			final JMenuItem item6 = new JMenuItem( "Previous image marked as invalid" );
			item5.addActionListener( e -> state2.prevImageWithFeature( FeatureState.NOT_ASSIGNED ) );
			item6.addActionListener( e -> state2.prevImageWithFeature( FeatureState.INVALID ) );
			popupMenu4.add( item5 );
			popupMenu4.add( item6 );
			state2.back.setComponentPopupMenu( popupMenu4 );

			// GRID Y=3
			c.gridx = 0;
			c.gridy = 3;
			c.gridwidth = 4*2;
			c.gridheight = 1;
			state2.featureLabel =
					new JLabel( state2.featureLabel(), SwingConstants.CENTER );
			state2.testAndUpdateFeatureComplete();
			state2.featureLabel.setOpaque(true);
			state2.featureLabel.setBorder( BorderFactory.createLineBorder(Color.BLACK, 1) );
			state2.dialog.add( state2.featureLabel, c );
			
			// GRID Y=4
			c.gridx = 0;
			c.gridy = 4;
			c.gridwidth = 4*2;
			c.gridheight = 1;
			state2.featureDescMinusOne = new JLabel( state2.featureDescMinus1(), SwingConstants.CENTER );
			state2.featureDescMinusOne.setFont( new Font( state2.featureDescMinusOne.getFont().getName(), Font.PLAIN, state2.featureDescMinusOne.getFont().getSize() - 2) );
			state2.dialog.add( state2.featureDescMinusOne, c );

			// GRID Y=5
			c.gridx = 0;
			c.gridy = 5;
			c.gridwidth = 4*2;
			c.gridheight = 1;
			state2.featureDescZero = new JLabel( state2.featureDescZero(), SwingConstants.CENTER );
			state2.featureDescZero.setFont( new Font( state2.featureDescZero.getFont().getName(), Font.PLAIN, state2.featureDescZero.getFont().getSize() - 2) );
			state2.dialog.add( state2.featureDescZero, c );

			// GRID Y=6
			c.gridx = 0;
			c.gridy = 6;
			c.gridwidth = 4*2;
			c.gridheight = 1;
			state2.featureDescPlusOne = new JLabel(  state2.featureDescPlus1(), SwingConstants.CENTER );
			state2.featureDescPlusOne.setFont( new Font( state2.featureDescPlusOne.getFont().getName(), Font.PLAIN, state2.featureDescPlusOne.getFont().getSize() - 2) );
			state2.dialog.add( state2.featureDescPlusOne, c );

			// GRID Y=7
			c.gridx = 0;
			c.gridy = 7;
			c.gridwidth = 1;
			state2.buttonMinus1 = new JButton( " - " );
			state2.buttonMinus1.setFont( state2.buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			state2.buttonMinus1.setForeground( Color.magenta );
			state2.buttonMinus1.addActionListener( e -> state2.setFeatureState( FeatureState.NEGATIVE ) );
			state2.dialog.add( state2.buttonMinus1, c );

			c.gridx = 1;
			c.gridy = 7;
			c.gridwidth = 1;
			state2.buttonZero = new JButton( " 0 " );;
			state2.buttonZero.setFont( state2.buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			state2.buttonZero.setForeground( Color.magenta );
			state2.buttonZero.addActionListener( e -> state2.setFeatureState( FeatureState.ZERO ) );
			state2.dialog.add( state2.buttonZero, c );

			c.gridx = 2;
			c.gridy = 7;
			c.gridwidth = 1;
			state2.buttonPlus1 = new JButton( " + " );;
			state2.buttonPlus1.setFont( state2.buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			state2.buttonPlus1.setForeground( Color.magenta );
			state2.buttonPlus1.addActionListener( e -> state2.setFeatureState( FeatureState.POSITIVE ) );
			state2.dialog.add( state2.buttonPlus1, c );

			c.gridx = 3;
			c.gridy = 7;
			c.gridwidth = 1;
			state2.placeholder1 = new JLabel( "                  " );;
			state2.dialog.add( state2.placeholder1, c );

			c.gridx = 4;
			c.gridy = 7;
			c.gridwidth = 1;
			state2.placeholder2 = new JLabel( "                  " );;
			state2.dialog.add( state2.placeholder2, c );

			c.gridx = 5;
			c.gridy = 7;
			c.gridwidth = 1;
			state2.buttonPrevFeature = new JButton( " -F " );;
			state2.buttonPrevFeature.setFont( state2.buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			state2.buttonPrevFeature.setForeground( Color.GREEN.darker().darker().darker() );
			state2.buttonPrevFeature.addActionListener( e -> state2.prevFeature() );
			final JPopupMenu popupMenu1 = new JPopupMenu();
			final JMenuItem item1 = new JMenuItem( "Previous un-annotated feature" );
			popupMenu1.add( item1 );
			item1.addActionListener( e -> state2.prevUnannotatedFeature() );
			state2.buttonPrevFeature.setComponentPopupMenu( popupMenu1 );
			state2.dialog.add( state2.buttonPrevFeature, c );

			c.gridx = 6;
			c.gridy = 7;
			c.gridwidth = 1;
			state2.buttonNextFeature = new JButton( " +F " );;
			state2.buttonNextFeature.setFont( state2.buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			state2.buttonNextFeature.setForeground( Color.GREEN.darker().darker().darker() );
			state2.buttonNextFeature.addActionListener( e -> state2.nextFeature() );
			final JPopupMenu popupMenu2 = new JPopupMenu();
			final JMenuItem item2 = new JMenuItem( "Next un-annotated feature" );
			item2.addActionListener( e -> state2.nextUnannotatedFeature() );
			popupMenu2.add( item2 );
			state2.buttonNextFeature.setComponentPopupMenu( popupMenu2 );
			state2.dialog.add( state2.buttonNextFeature, c );

			c.gridx = 7;
			c.gridy = 7;
			c.gridwidth = 1;
			state2.buttonNextImage = new JButton( " X " );;
			state2.buttonNextImage.setFont( state2.buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			state2.buttonNextImage.setForeground( Color.RED );
			state2.buttonNextImage.addActionListener( e -> state2.setAllFeatureStatesInvalid());
			state2.dialog.add( state2.buttonNextImage, c );

			// GRID Y=8
			c.gridx = 0;
			c.gridy = 8;
			c.gridwidth = 1;
			state2.barMinus1 = new JLabel("     ");
			state2.barMinus1.setFont( new Font( "Arial", Font.PLAIN, 6 ) );
			state2.barMinus1.setBackground( Color.lightGray );
			state2.barMinus1.setOpaque( true );
			state2.dialog.add( state2.barMinus1, c );

			c.gridx = 1;
			c.gridy = 8;
			c.gridwidth = 1;
			state2.barZero = new JLabel("     ");
			state2.barZero.setFont( new Font( "Arial", Font.PLAIN, 6 ) );
			state2.barZero.setBackground( Color.lightGray );
			state2.barZero.setOpaque( true );
			state2.dialog.add( state2.barZero, c );

			c.gridx = 2;
			c.gridy = 8;
			c.gridwidth = 1;
			state2.barPlus1 = new JLabel("     ");
			state2.barPlus1.setFont( new Font( "Arial", Font.PLAIN, 6 ) );
			state2.barPlus1.setBackground( Color.lightGray );
			state2.barPlus1.setOpaque( true );
			state2.dialog.add( state2.barPlus1, c );

			// global key listener
			long eventMask = AWTEvent.KEY_EVENT_MASK;

			Toolkit.getDefaultToolkit().addAWTEventListener(e ->
			{
				if ( e.getID() == KeyEvent.KEY_PRESSED )
				{
					final KeyEvent ke = (KeyEvent)e;

					if ( ke.getKeyChar() == 'a' )
						state2.setFeatureState( FeatureState.NEGATIVE );
					else if ( ke.getKeyChar() == 's' )
						state2.setFeatureState( FeatureState.ZERO );
					else if ( ke.getKeyChar() == 'd' )
						state2.setFeatureState( FeatureState.POSITIVE );
					else if ( ke.getKeyChar() == '>' )
						state2.nextFeature();
					else if ( ke.getKeyChar() == '<' )
						state2.prevFeature();
					else if ( ke.getKeyChar() == 'X' )
						state2.setAllFeatureStatesInvalid();
				}
			}, eventMask);
		}

		// show dialog
		state.dialog.pack();
		state.dialog.setVisible(true);


		// setup ImageJ window
		this.main = new HashMap<>();
		this.stack = new ImageStack();

		// create empty imagestack
		for ( int f = 0; f <= maxFrame; ++f )
		{
			final ColorProcessor cp = new ColorProcessor( imgsA[ 0 ].getWidth(), imgsA[ 0 ].getHeight());
			this.main.put( f, cp );
			this.stack.addSlice( cp );
		}

		// create image window
		this.mainImp = new ImagePlus( state.impDialog + "0", this.stack );

		// setup calibration for animation
		final Calibration cal = this.mainImp.getCalibration();
		cal.fps = 25;
		cal.loop = true;

		// fill image stack with interpolated image data
		interpolateMainImage( state );

		// setup overlays, add listener
		final Overlay ov = new Overlay();
		this.mainImp.setOverlay( ov );

		final Font font = new Font(" SansSerif", Font.PLAIN, 26);

		final TextRoi textROIA = new TextRoi(135, 12, "A", font );
		textROIA.setStrokeColor( new Color( 255, 255, 255 ) );
		ov.add( textROIA );

		final TextRoi textROIB = new TextRoi(136, 85, "B", font );
		textROIB.setStrokeColor( new Color( 255, 0, 0 ) );
		ov.add( textROIB );

		SliceListener sliceListener = l ->
		{
			final int frame = this.mainImp.getZ() - 1;

			final float c1 = (float)frame / (float)maxFrame;
			final float c2 = 1.0f - c1;

			textROIA.setStrokeColor( new Color( c2, c2, c2 ) );
			textROIB.setStrokeColor( new Color( c1, c1, c1 ) );
		};
		sliceObserver = new SliceObserver(this.mainImp, sliceListener );

		// show main window
		this.mainImp.show();

		new Thread(() ->
		{
			// default maginfication (is slow)
			Zoom.set( this.mainImp, defaultMagnification );

			// start animation
			new Animator().run( "start" );
		}).start();

		state.dialog.requestFocus();
	}

	public static String defaultDirectory = "";
	public static String defaultJSON = "";
	public static boolean defaultLoadExisting = true;

	@Override
	public void run(String arg)
	{
		GenericDialogPlus gd = new GenericDialogPlus( "Select base directory" );

		gd.addDirectoryField("Directory", defaultDirectory, 80 );
		gd.addFileField( "Annotation JSON", defaultJSON, 80 );
		gd.addCheckbox( "Load existing state (notes/selections)", defaultLoadExisting);

		gd.showDialog();
		if ( gd.wasCanceled() )
			return;

		setup( defaultDirectory = gd.getNextString() );
		SwingUtilities.invokeLater(() ->
			this.showDialog( 100, 3.0, 50, Color.orange, defaultLoadExisting = gd.getNextBoolean(), defaultJSON = gd.getNextString() ) );

		SwingUtilities.invokeLater(() -> IJ.log( "\nNote: keyboard-shortcuts are 'a', 's', 'd' for features -1, 0, +1; '>', '<' for next/prev feature, and 'X' for marking the current image as invalid."));
	}

	@Override
	public void run() { run( null ); }

	public static void main( String[] args )
	{
		//try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception e) { }

		new ImageJ();
		defaultDirectory = "/Users/preibischs/Documents/Janelia/Projects/Funke/phase1/";
		defaultJSON = "/Users/preibischs/Documents/Janelia/Projects/Funke/phase2_example.json";

		new MLTool().run( null );

		//final MLTool tool = new MLTool();
		//tool.setup( "/Users/preibischs/Documents/Janelia/Projects/Funke/phase1/" );
		//SwingUtilities.invokeLater(() -> tool.showDialog( 100, 3.0, 50, Color.orange ) );

		/*
		ImagePlus imp1 = new ImagePlus( "/Users/preibischs/Documents/Janelia/Projects/Funke/image001.png");
		//ImagePlus imp2 = new ImagePlus( "/Users/preibischs/Documents/Janelia/Projects/Funke/image002.png");
		//ImagePlus mask = new ImagePlus( "/Users/preibischs/Documents/Janelia/Projects/Funke/image003.png");
		//setImages( (ByteProcessor)imp1.getProcessor(), (ByteProcessor)imp2.getProcessor(), (ByteProcessor)mask.getProcessor() );

		Overlay ov = new Overlay();
		imp1.setOverlay( ov );
		
		final Font font = new Font(" SansSerif", Font.PLAIN, 26);
		Color color = new Color( 1.00f, 1.00f, 1.00f );

		final TextRoi textROI = new TextRoi(50, 60, "A", font );
		textROI.setStrokeColor( color );
		ov.add( textROI );

		imp1.show();

		for ( int i = 128; i < 256; ++i )
		{
			SimpleMultiThreading.threadWait( 10 );
			color = new Color(i, i, i);
			textROI.setStrokeColor( color );
			imp1.updateAndDraw();
		}
		//final OvalRoi or = new OvalRoi(20, 20, 30, 30 );
		//or.setStrokeColor( Color.red );
		//ov.add(or);
		imp1.updateAndDraw();
		*/

	}
}
