package util;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JSlider;

public abstract class GUIState
{
	final public String labelDialog = "Annotation for image ";
	final public String impDialog = "Image ";

	public final MLTool tool;

	public JDialog dialog;
	public JSlider sliderImg, sliderMask;
	public JButton text1, text2, text3, text4;
	public JButton back, forward, save, quit;

	public GUIState( final MLTool tool )
	{
		this.tool = tool;
	}

	public int currentImage() { return sliderImg.getValue(); }

	public void nextImage() { sliderImg.setValue( sliderImg.getValue() + 1); }

	public void prevImage() { sliderImg.setValue( sliderImg.getValue() - 1); }

	public void setImage()
	{
		final int imageIndex = currentImage();

		tool.setImages( tool.imgsA[ imageIndex ], tool.imgsB[ imageIndex ], tool.imgsM[ imageIndex ] );
		tool.interpolateMainImage( this );
		dialog.setTitle( labelDialog + imageIndex );
		tool.mainImp.setTitle( impDialog + imageIndex );

		notifyImageChanged();
	}

	public abstract void notifyImageChanged();
	public abstract boolean save( final String dir );
	public abstract boolean load( final String dir );
}
