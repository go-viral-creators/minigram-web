package com.silu.minigram;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.*;

import java.util.ArrayList;

/**
* DrawingView
* WhatsApp-style drawing overlay view.
* Supports: Pen, Eraser, Undo
* Place this on top of an ImageView in FrameLayout.
*/
public class DrawingView extends View {
	
	private static class DrawPath {
		Path  path;
		Paint paint;
		DrawPath(Path p, Paint pt) { path = p; paint = pt; }
	}
	
	private final ArrayList<DrawPath> paths    = new ArrayList<>();
	private final ArrayList<DrawPath> undoList = new ArrayList<>();
	
	private Path  currentPath;
	private Paint currentPaint;
	
	// Draw settings (change via setters)
	private int   strokeColor  = Color.RED;
	private float strokeWidth  = 14f;
	private boolean eraserMode = false;
	
	public DrawingView(Context ctx) { super(ctx); init(); }
	public DrawingView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }
	public DrawingView(Context ctx, AttributeSet attrs, int def) { super(ctx, attrs, def); init(); }
	
	private void init() {
		setLayerType(View.LAYER_TYPE_HARDWARE, null);
	}
	
	private Paint makePaint() {
		Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
		p.setStyle(Paint.Style.STROKE);
		p.setStrokeJoin(Paint.Join.ROUND);
		p.setStrokeCap(Paint.Cap.ROUND);
		if (eraserMode) {
			p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
			p.setStrokeWidth(strokeWidth * 2.5f);
		} else {
			p.setColor(strokeColor);
			p.setStrokeWidth(strokeWidth);
			p.setXfermode(null);
		}
		return p;
	}
	
	public void setStrokeColor(int color)  { this.strokeColor = color; eraserMode = false; }
	public void setStrokeWidth(float w)    { this.strokeWidth = w; }
	public void setEraserMode(boolean on)  { this.eraserMode = on; }
	public boolean isEraserMode()          { return eraserMode; }
	public boolean isEmpty()               { return paths.isEmpty(); }
	
	public void undo() {
		if (!paths.isEmpty()) {
			undoList.add(paths.remove(paths.size() - 1));
			invalidate();
		}
	}
	
	public void redo() {
		if (!undoList.isEmpty()) {
			paths.add(undoList.remove(undoList.size() - 1));
			invalidate();
		}
	}
	
	public void clear() {
		paths.clear();
		undoList.clear();
		invalidate();
	}
	
	public Bitmap exportBitmap() {
		Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		this.draw(canvas);
		return bitmap;
	}
	
	/** Get the drawing as a transparent bitmap (same size as this view) */
	public Bitmap getDrawingBitmap() {
		Bitmap bmp = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(bmp);
		// Draw with CLEAR mode needs software layer
		c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
		for (DrawPath dp : paths) c.drawPath(dp.path, dp.paint);
		return bmp;
	}
	
	@Override
	protected void onDraw(Canvas canvas) {
		for (DrawPath dp : paths) canvas.drawPath(dp.path, dp.paint);
		if (currentPath != null && currentPaint != null)
		canvas.drawPath(currentPath, currentPaint);
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		float x = event.getX(), y = event.getY();
		switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN:
			currentPath  = new Path();
			currentPaint = makePaint();
			currentPath.moveTo(x, y);
			undoList.clear();
			break;
			case MotionEvent.ACTION_MOVE:
			if (currentPath != null) currentPath.lineTo(x, y);
			break;
			case MotionEvent.ACTION_UP:
			if (currentPath != null) {
				currentPath.lineTo(x, y);
				paths.add(new DrawPath(currentPath, currentPaint));
				currentPath  = null;
				currentPaint = null;
			}
			break;
		}
		invalidate();
		return true;
	}
}
