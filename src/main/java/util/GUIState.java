package util;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JSlider;

public abstract class GUIState
{
	final public String labelDialog = "Annotation for image ";
	final public String impDialog = "Image ";

	public JDialog dialog;
	public JSlider sliderImg, sliderMask;
	public JButton text1, text2, text3, text4;
	public JButton back, forward, save, quit;

	public abstract boolean save( final String dir );
	public abstract boolean load( final String dir );
}
