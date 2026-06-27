package com.example.v2_sudoku_acp_android;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.FileOutputStream;

public class EditImageActivity extends AppCompatActivity {

    private PaintView paintView;
    private ImageButton btnMode;
    private Button btnColor;
    private SeekBar sbBrushSize;
    private TextView tvSizeValue;
    
    private boolean isPaintMode = true;
    private boolean isBlackColor = true;
    private String imagePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_image);

        paintView = findViewById(R.id.paintView);
        btnMode = findViewById(R.id.btnMode);
        btnColor = findViewById(R.id.btnColor);
        sbBrushSize = findViewById(R.id.sbBrushSize);
        tvSizeValue = findViewById(R.id.tvSizeValue);
        
        ImageButton btnUndo = findViewById(R.id.btnUndo);
        ImageButton btnSave = findViewById(R.id.btnSave);

        imagePath = getIntent().getStringExtra("image_path");
        if (imagePath != null) {
            Bitmap bmp = BitmapFactory.decodeFile(imagePath);
            if (bmp != null) {
                paintView.setImage(bmp);
            }
        }

        btnMode.setOnClickListener(v -> {
            isPaintMode = !isPaintMode;
            paintView.setMode(isPaintMode ? PaintView.Mode.PAINT : PaintView.Mode.ZOOM);
            btnMode.setImageResource(isPaintMode ? android.R.drawable.ic_menu_edit : android.R.drawable.ic_menu_search);
            btnMode.setColorFilter(isPaintMode ? Color.parseColor("#6200EE") : Color.WHITE);
            findViewById(R.id.llBrushSize).setVisibility(isPaintMode ? View.VISIBLE : View.GONE);
        });

        btnColor.setOnClickListener(v -> {
            isBlackColor = !isBlackColor;
            int color = isBlackColor ? Color.BLACK : Color.WHITE;
            paintView.setColor(color);
            // Use setSupportBackgroundTintList if it's an AppCompat button, 
            // or just use setBackgroundTintList with a ColorStateList for Material buttons.
            btnColor.setBackgroundTintList(ColorStateList.valueOf(color));
            
            // If the color is white, we might want to add a small outline or change the parent color
            // but the backgroundTint should work now.
        });

        sbBrushSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float size = Math.max(1, progress);
                paintView.setStrokeWidth(size);
                tvSizeValue.setText(String.valueOf((int)size));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnUndo.setOnClickListener(v -> paintView.undo());
        btnSave.setOnClickListener(v -> saveAndFinish());
        
        // Initial setup
        paintView.setColor(Color.BLACK);
        btnColor.setBackgroundTintList(ColorStateList.valueOf(Color.BLACK));
        paintView.setStrokeWidth(20f);
        sbBrushSize.setProgress(20);
    }

    private void saveAndFinish() {
        Bitmap finalBitmap = paintView.getBitmap();
        if (finalBitmap != null) {
            try (FileOutputStream out = new FileOutputStream(imagePath)) {
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                Intent resultIntent = new Intent();
                resultIntent.putExtra("image_path", imagePath);
                setResult(RESULT_OK, resultIntent);
                finish();
            } catch (Exception e) {
                Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
