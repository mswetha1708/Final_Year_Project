package com.example.project_swetha;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class ChooseActivity extends AppCompatActivity {

    int activity;
    private Button inputButton, yoloButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chooser);
        inputButton = (Button) findViewById(R.id.input_button);
        yoloButton = (Button) findViewById(R.id.yolo_button);

        inputButton.setOnClickListener(inputButtonOnClickListener);
        yoloButton.setOnClickListener(yoloButtonOnClickListener);
    }

    private final View.OnClickListener inputButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
                Intent intent = new Intent(ChooseActivity.this, Recording.class);
//                Bundle b = new Bundle();
//                b.putInt("key", 2); //Your id
//                intent.putExtras(b);
                startActivity(intent);
            }
    };

    private final View.OnClickListener yoloButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            Intent intent = new Intent(ChooseActivity.this, FilePicker.class);
                Bundle b = new Bundle();
                b.putInt("key", 4); //Your id
                intent.putExtras(b);
            startActivity(intent);
        }
    };

}
