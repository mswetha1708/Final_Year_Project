package com.example.project;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.location.GnssStatus;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;

import com.example.project.detection.customview.OverlayView;
import com.example.project.detection.env.ImageUtils;
import com.example.project.detection.env.Utils;
import com.example.project.detection.tflite.Classifier;
import com.example.project.detection.tflite.YoloV4Classifier;
import com.example.project.detection.tracking.MultiBoxTracker;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVWriter;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.api.android.AndroidSequenceEncoder;
import org.jcodec.common.AndroidUtil;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Rational;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.TimeZone;

import io.ticofab.androidgpxparser.parser.GPXParser;
import io.ticofab.androidgpxparser.parser.domain.Gpx;
import io.ticofab.androidgpxparser.parser.domain.Track;
import io.ticofab.androidgpxparser.parser.domain.TrackPoint;
import io.ticofab.androidgpxparser.parser.domain.TrackSegment;

public class Recording extends AppCompatActivity {
    // Mode configuration settings
    //Common
    private int VIDEO_FORMAT = MediaRecorder.OutputFormat.MPEG_4;
    private long LOCATION_INTERVAL = 1000; // How frequently should location be stored with respect to final video

    //Video Mode
    private int VIDEO_FPS = 25;
    private int VIDEO_QUALITY;
    private HashMap<String, Integer> availableVideoQualities;
    private float crop_height;
    private float crop_width;


    //Timelapse values - Populated based on Camcorder advertised hardware capabilites
    private long TIME_LAPSE_FPS;
    private int PLAYBACK_FRAME_RATE; // Number of FPS when video is played
    private int TIME_LAPSE_QUALITY;
    private HashMap<String, Integer> availableTimeLapseQualities;



    private static final int VIDEO_MODE = 1;
    private static final int TIMELAPSE_MODE = 2;
    long FREQUENCY = 1000; // frequency at which location is queried
    int mode;




    //Msg codes;
    private static final int INSTRUCTIONS = 1;
    private static final int FIX_PENDING = 2;
    private static final int FIX_INFO = 3;
    private static final int EXPERIMENTAL_TIME_LAPSE_MODE = 4;

    private String filename;

    //Accuracy parameter - hash map of hash map - number of occurance, number of mismatch and misinterpretations.
    private HashMap<Integer, List<Integer>> accuracy = new HashMap<Integer,List<Integer>>();

    // Camera parameter
    Camera mCamera;
    int currentCameraId;
    CameraPreview mPreview;
    int currentCameraOrientationResult;

    //  Mediarecorder
    MediaRecorder mediaRecorder;

    // Files
    FileOutputStream fos;
    XmlSerializer serializer;
    SimpleDateFormat sdf;


    // Location parameters
    private LocationManager mlocManager = null;
    private boolean isGPSLocationUpdatesActive = false;
    private long mLastLocationMillis;
    private long currentRecordingStartTime;
    private long prevLocSavedTime = -1; // the time corresponding to previously saved location in gpx file
    private boolean hasGPSFix = false;
    Location mLastLocation = null;

    // Record parameters
    Button recordButton;
    Button toggleFlashButton;
    Button switchCameraButton;
    boolean isRecording = false;
    boolean forceRec = false;
    boolean gotFirstFix = true;

    Button fileButton;
    Button aboutButton;

    // Imageview doesn't display tick because of issue with resolution-- bitmap issue most probably
    Button tickView;
    Button mProgressBar;

    Button modeButton;

    // Qauality - Only applicable when using back camera
    TextView quality_text;

    //gpx File to text processing
    ListIterator<TrackPoint> gpxTrackPointsIterator;
    List<TrackPoint> gpxTrackPoints;
    TrackPoint currentGpxPoint;

    //Tensorflow Application parameters
    public static final int TF_OD_API_INPUT_SIZE = 416;

    private static final boolean TF_OD_API_IS_QUANTIZED = false;

    private static final String TF_OD_API_MODEL_FILE = "yolov4-416-fp32.tflite";
    public static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;
    private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/coco.txt";
    private static final boolean MAINTAIN_ASPECT = false;
    private Integer sensorOrientation = 90;
    private Classifier detector;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;
    private MultiBoxTracker tracker;
    private OverlayView trackingOverlay;

    protected int previewWidth = 0;
    protected int previewHeight = 0;

    private Bitmap sourceBitmap;
    private Bitmap cropBitmap;

    private Button cameraButton, detectButton;
    private ImageView imageView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        setContentView(R.layout.activity_recording);

        //Check if location is enabled
        if(!isLocationEnabled()){
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
        }


        //Setting the time format
        sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SS'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));


        //Initializing buttons and listeners
        recordButton = (Button) findViewById(R.id.record_button);
        switchCameraButton = (Button) findViewById(R.id.switch_cam_button);
        toggleFlashButton = (Button) findViewById(R.id.flash_button);
        fileButton = (Button) findViewById(R.id.files_button);
        aboutButton = (Button) findViewById(R.id.about_button);

        //GPS fix status progress bar
        tickView = (Button) findViewById(R.id.tickView);
        mProgressBar = (Button) findViewById(R.id.progressBar);

        //Toggling record button to start and stop recording
        recordButton.setOnClickListener(recordButtonOnClickListener);

        switchCameraButton.setOnClickListener(switchCameraListener);
        toggleFlashButton.setOnClickListener(toggleFlashlightListener);

        // Launch Filepicker activity on clicking
        fileButton.setOnClickListener(fileButtonOnClickListener);

        // Help/About Button
        aboutButton.setOnClickListener(aboutButtonOnClickListener);

        mProgressBar.setOnClickListener(GpsFixListener);
        tickView.setOnClickListener(GpsFixListener);

        modeButton = findViewById(R.id.mode_button);
        modeButton.setOnClickListener(modeChangeListener);

        // Qaulity chooser
        quality_text = findViewById(R.id.qualityText);
        quality_text.setOnClickListener(qualityTextListener);

        initialize_app_folder();
        // Default camera is set as back camera
        currentCameraId = getBackCameraID();
        initialize_camera(currentCameraId);
        initialize_location();


        // Get available qualities
        availableVideoQualities = getAvailableVideoQualities();
        availableTimeLapseQualities = getAvailableTimeLapseQualities();

        set_mode(VIDEO_MODE);
    }


    @Override
    protected void onPause() {
        super.onPause();
        releaseMediaRecorder();       // if you are using MediaRecorder, release it first
        finish_gpx_file();
        stopPreview();
        recordButton.setBackgroundResource(R.drawable.rec);
        isRecording = false;
        // Unlock screen rotate
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        setGPSLocationUpdates(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        //Restart location updates
        // Reinitialize Camera
        initialize_camera(currentCameraId);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseCamera();              // release the camera only on destroy event
    }

    //Detecting and adjusting preview camera based on orientation
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        currentCameraOrientationResult = getCameraDisplayOrientation(this, currentCameraId);

        Log.d("Cam", Integer.toString(currentCameraOrientationResult));
        mCamera.setDisplayOrientation(currentCameraOrientationResult);
    }
    // OnClick listeners

    private final View.OnClickListener recordButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (isRecording) {
                recordButton.setEnabled(false);
                try {
                    stop_rec_and_release();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                recordButton.setEnabled(true);
            }
             else {
                //Check if location is enabled
                if(!isLocationEnabled()){
                    Toast.makeText(getApplicationContext(),"Ensure location mode is set to High Accuracy", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    startActivity(intent);

                }
                else{

                    if (hasGPSFix) {
                        gotFirstFix = true;
                        prepare_and_start_rec();
                    }
                    else{
                        // Display option for force recording
                        // Setting Dialog Title
                        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(Recording.this,R.style.DialogTheme);
                        alertBuilder.setTitle("Force Record");
                        String msg = "GPS is yet to get a fix on your location." + "\n" +
                                "GPS Fix can be delayed if you are indoors or surrounded by tall building." + "\n" +
                                "Recording without a fix may result in points with less accuracy." + "\n" +
                                "It is highly recommended you wait for a GPS fix";
                        // Setting Dialog Message
                        alertBuilder.setMessage(msg);

                        alertBuilder.setPositiveButton("Proceed", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                forceRec = true;
                                gotFirstFix = false;
                                prepare_and_start_rec();
                            }
                        });

                        alertBuilder.setNegativeButton("Wait", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                dialogInterface.dismiss();
                            }
                        });


                        AlertDialog dialog = alertBuilder.create();
                        dialog.show();
                    }
                }
            }
        }
    };

    private final View.OnClickListener fileButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            if (isRecording){
                Toast.makeText(Recording.this,"Recording in progress", Toast.LENGTH_SHORT).show();
            }
            else{
                Intent intent = new Intent(Recording.this, FilePicker.class);
                Bundle b = new Bundle();
                b.putInt("key", 1); //Your id
                intent.putExtras(b);
                startActivity(intent);
            }
        }
    };

    private final View.OnClickListener aboutButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            if (isRecording){
                Toast.makeText(Recording.this,"Recording in progress", Toast.LENGTH_SHORT).show();
            }
            else{
                Intent intent = new Intent(Recording.this, FilePicker.class);
                Bundle b = new Bundle();
                b.putInt("key", 2); //Your id
                intent.putExtras(b);
                startActivity(intent);
            }
        }
    };


    private final View.OnClickListener GpsFixListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            display_alert(FIX_INFO);
        }
    };


    private final View.OnClickListener modeChangeListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            if (isRecording){
                Toast.makeText(Recording.this,"Recording in progress", Toast.LENGTH_SHORT).show();
            }
            else{
                Intent intent = new Intent(Recording.this, FilePicker.class);
                Bundle b = new Bundle();
                b.putInt("key", 3); //Your id
                intent.putExtras(b);
                startActivity(intent);
            }
        }
    };

    private final View.OnClickListener toggleFlashlightListener = new View.OnClickListener(){
        @Override
        public void onClick(View view) {
            // Dont do anything if front camera
            // For front camera, getParamaters returns null
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(currentCameraId, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT){
                return;
            }

            // Dont do anything if flashlight feature is absent
            if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)){
                return;
            }

            Camera.Parameters params = mCamera.getParameters();

            // Toggling
            if (params.getFlashMode().equals(Camera.Parameters.FLASH_MODE_TORCH)){
                params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            }
            else{
                params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            }

            mCamera.setParameters(params);
            mCamera.startPreview();
        }
    };

    private final View.OnClickListener switchCameraListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (isRecording){
                Toast.makeText(getApplicationContext(), "Recording in Progress", Toast.LENGTH_SHORT);
                return;
            }


            if (mCamera != null) {
                ConstraintLayout preview = (ConstraintLayout) findViewById(R.id.camera_preview);
                preview.removeView(mPreview);
                mPreview = null;

                mCamera.stopPreview();
                //NB: if you don't release the current camera before switching, you app will crash
                mCamera.release();

                //swap the id of the camera to be used
                if(currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK){
                    currentCameraId = getFrontCameraID();
                    // Show quality option
                    quality_text.setVisibility(View.INVISIBLE);
                }
                else {
                    currentCameraId = getBackCameraID();
                    // Hide quality option
                    quality_text.setVisibility(View.VISIBLE);
                }


                // Reinitialize with changed cameraId
                initialize_camera(currentCameraId);
            }
        }
    };

    private final View.OnClickListener qualityTextListener = new View.OnClickListener() {
        // Show popup to select quality
        // Currently only supported for Back camera
        @Override
        public void onClick(View v) {
            if(isRecording){
                return;
            }

            final ArrayList<String> available = new ArrayList<>();

            if (mode==VIDEO_MODE){
                for (HashMap.Entry<String, Integer> entry : availableVideoQualities.entrySet()){
                    available.add(entry.getKey());
                }
            }
            else if (mode == TIMELAPSE_MODE){
                for (HashMap.Entry<String, Integer> entry : availableTimeLapseQualities.entrySet()){
                    available.add(entry.getKey());
                }
            }



            AlertDialog.Builder builder = new AlertDialog.Builder(Recording.this,R.style.DialogTheme);
            builder.setTitle("Select quality");
            builder.setItems(available.toArray(new String[0]), new DialogInterface.OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String selected_quality = available.get(which);
                    updatePreferredQuality(selected_quality);
                }
            });
            builder.show();
        }
    };

    //Recording start and stop functions
    private void prepare_and_start_rec(){

        filename = "REC-" + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());

        // Initialize video camera
        if (prepareVideoRecorder()) {

            // Lock screen orientation
            lockOrientation(this);

            currentRecordingStartTime = System.currentTimeMillis();
            // Camera is available and unlocked, MediaRecorder is prepared,
            // now you can start recording
            mediaRecorder.start();

            //Create new GPX file and start recording location data
            create_gpx_file(filename + ".gpx");
            isRecording = true;

            // Inform the user that recording has started
            recordButton.setBackgroundResource(R.drawable.stop);

//                            //Hide GPS fix status spinner while recording
            mProgressBar.setVisibility(View.INVISIBLE);
            tickView.setVisibility(View.INVISIBLE);

        } else {

            // If prepare didn't work, release the camera
            releaseMediaRecorder();
            // Inform user
            Toast.makeText(getApplicationContext(), "Some Error Occured", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap apply_location(Bitmap photo, int frameNumber, org.joda.time.DateTime startTime, CSVWriter writer)
    {
        Canvas canvas = new Canvas(photo);
        canvas.drawBitmap(photo, 0, 0, null);
        float scale = getResources().getDisplayMetrics().density;

        Paint p = new Paint();
        //Change font size and gap
        p.setTypeface(Typeface.create("Arial",Typeface.BOLD));
        p.setTextSize(60);
        Paint.FontMetrics fm = new Paint.FontMetrics();
        p.setColor(Color.BLACK);
        p.getFontMetrics(fm);
        int margin = 20;
        // Read from GPX Parser and get lattitude and longitude
        //use time for comparison - Get frame number using fps*current time
        //store prev latitude and longitude
        //Calculation wrong
        Log.d("Info:", String.valueOf(currentGpxPoint.getTime().getMillis()));
        Log.d("Info:", String.valueOf(startTime.getMillis()));
        long delay = currentGpxPoint.getTime().getMillis() - startTime.getMillis();
        long diff = (delay * 30) - frameNumber;

        if(delay * 30/1000 > frameNumber || frameNumber == 0 || gpxTrackPointsIterator.hasNext()==false) {
            Log.d("Info", "To be filled");
            Log.d("Info", "To be filled");
        }
        else
        {
//            Log.d("Next time", String.valueOf(gpxTrackPointsIterator.next().getTime()));
            currentGpxPoint = gpxTrackPointsIterator.next();
            Log.d("Next time", String.valueOf(currentGpxPoint.getTime()));
        }
        //Print elevation
        String latitude = String.valueOf(currentGpxPoint.getLatitude());
        String longitude = String.valueOf(currentGpxPoint.getLongitude());
        Log.d("Latitude", String.valueOf(currentGpxPoint.getElevation()));
        Log.d("Latitude", String.valueOf(currentGpxPoint.getLatitude()));
        Log.d("Longitude", String.valueOf(currentGpxPoint.getLongitude()));
        //Write to CSV File
        String[] data1 = {String.valueOf(frameNumber), String.valueOf(currentGpxPoint.getLatitude()),String.valueOf(currentGpxPoint.getLongitude())};
        writer.writeNext(data1);

        canvas.drawRect(5 - margin, 30 + fm.top - margin,
                5 + p.measureText(latitude+", "+ longitude) + margin, 15 + fm.bottom
                        + margin, p);
        p.setColor(Color.WHITE);
        canvas.drawText(latitude+", "+ longitude , 10,50,p);

        return photo;
    }

    private String get_text_from_image(Bitmap bitmap)
    {
        TextRecognizer txtRecognizer = new TextRecognizer.Builder(getApplicationContext()).build();
        if (!txtRecognizer.isOperational())
        {
            // Shows if your Google Play services is not up to date or OCR is not supported for the device
            Log.d("Info","Detector dependencies are not yet available");
        }
        else
        {
            // Set the bitmap taken to the frame to perform OCR Operations.
            Frame frame = new Frame.Builder().setBitmap(bitmap).build();
            SparseArray items = txtRecognizer.detect(frame);
            StringBuilder strBuilder = new StringBuilder();
            for (int i = 0; i < items.size(); i++)
            {
                TextBlock item = (TextBlock)items.valueAt(i);
                strBuilder.append(item.getValue());
                // The following Process is used to show how to use lines & elements as well
            }
            Log.d("Debug",(strBuilder.toString()));
            return strBuilder.toString();
        }
        return "";
    }

    private void convert_to_csv(int i, CSVWriter writer, String extracted_text,  List<String[]> allData) {

            // Split extract Text when a coma is found
            String [] data = extracted_text.split(",");
            //Original Value, New Value
            if(allData.size()>i) {
                String[] Original = allData.get(i);
                //Data1 find number of digits misclassified for each row.
                //Find the digit misclassified and add the total number of digits misclassified along with accuracy (0-9).
                if(Original.length <3 || data.length <2) {
                    return;
                }
                    Log.i("INFO", String.valueOf(i));
                    int latitude_error = mis_match(Original[1], data[0]);
                    int longitude_error = mis_match(Original[2], data[1]);

                //
                String[] data1 = {String.valueOf(i), String.valueOf(i), Original[1], Original[2], data[0], data[1],String.valueOf(latitude_error), String.valueOf(longitude_error)};
                writer.writeNext(data1);
            }

    }
    private void convert_to_csv_persons(int i, CSVWriter writer, String extracted_text,int count,  List<String[]> allData) {

        // Split extract Text when a coma is found
        String [] data = extracted_text.split(",");
        //Original Value, New Value
        if(allData.size()>i) {
            String[] Original = allData.get(i);
            //Data1 find number of digits misclassified for each row.
            //Find the digit misclassified and add the total number of digits misclassified along with accuracy (0-9).
            if(Original.length <3 || data.length <2) {
                return;
            }
            Log.i("INFO", String.valueOf(i));
            //
            String[] data1 = {String.valueOf(i),data[0], data[1],String.valueOf(count)};
            writer.writeNext(data1);
        }
    }
    private int mis_match(String original, String extracted)
    {
        int count =0;
        //Assume no missing characters
        //Remove space
        extracted = extracted.replace(" ", "");
        original = original.replace(" ", "");
        for(int i=0;i<original.length() && i<extracted.length();i++)
        {
            //Check if Integer
            char c = original.charAt(i);
            String s = String.valueOf(original.charAt(i));
            if(!Character.isDigit(c))
                continue;
            Integer r = Integer.parseInt(s);
                //If . do not consider
            Integer integer = Integer.parseInt(String.valueOf(original.charAt(i)));
            List<Integer> list = accuracy.get(integer);
            list.set(0, list.get(0) + Integer.valueOf(1));
            // Find total mismatch
            accuracy.put(integer,list);

         if(original.charAt(i) != extracted.charAt(i)) {
             count++;
             list.set(1,list.get(1) + Integer.valueOf(1));
             accuracy.put(integer, list);
         }
        }
        return count;
    }
    private CSVWriter create_csv_file() throws IOException {
        File csvfile = null;
        // Use OCR and add the details , frame number, coordinates in a csv.
        try {
            csvfile = new File(getExternalFilesDir(null) +
                    File.separator + "GPS_Video_Logger" + File.separator + filename +"after_Processing.csv");
            // if file doesnt exists, then create it
            if (!csvfile.exists()) {
                csvfile.createNewFile();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        //Add data and split value ','
        FileWriter fw = new FileWriter(csvfile.getAbsoluteFile());

        // create FileWriter object with file as parameter
        FileWriter outputfile = new FileWriter(csvfile);

        // create CSVWriter object filewriter object as parameter
        CSVWriter writer = new CSVWriter(outputfile);

        // adding header to csv
        String[] header = {"New Frame Number","Frame_Number","Original Latitude","Original Longitude", "Latitude", "Longitude","LatitudeError","Longitude Error"};
        // closing writer connection
        writer.writeNext(header);
        return writer;
    }
    private static int fixOrientation(Bitmap bitmap) {
        if (bitmap.getWidth() > bitmap.getHeight()) {
            return 270;
        }
        return 0;
    }
    public static Bitmap flipIMage(Bitmap bitmap) {
        //Moustafa: fix issue of image reflection due to front camera settings
        Matrix matrix = new Matrix();
        int rotation = fixOrientation(bitmap);
        matrix.postRotate(rotation);
        matrix.preScale(-1, 1);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }
    private void extract_images_store() throws  IOException {
        //enter correct location
//        InputStream inputStream = new FileInputStream();
        ArrayList<Bitmap> bmp_images = new ArrayList<Bitmap>();

        //To get frames from video
        String path = getExternalFilesDir(null) +
                File.separator + "GPS_Video_Logger" + File.separator + filename +".mp4";
        Log.i("Path:", path);
        File folder = new File(path);
        if (!folder.exists()) {
            File wallpaperDirectory = new File(path);
            wallpaperDirectory.mkdirs();
        }
        File file = new File(path);
        FrameGrab grab = null;
        try {
            grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(file));
        } catch (JCodecException e) {
            e.printStackTrace();
        }
        Picture picture;
        int i=0;
//
//
        //Open GPX file and get parser
        GPXParser parser = new GPXParser();
        Gpx gpx_parsed = null;
        FileInputStream fis;
        String gpx_filename = filename + ".gpx";

//
////Read from .gpx file created


        Log.d("Fileo",getExternalFilesDir(null) +
                File.separator + "GPS_Video_Logger" + "/" + gpx_filename);

        try {
            fis = new FileInputStream(new File( getExternalFilesDir(null) +
                    File.separator + "GPS_Video_Logger", gpx_filename));
            gpx_parsed = parser.parse(fis);
        } catch (IOException | XmlPullParserException e) {
            e.printStackTrace();
//            display_error_and_quit(GPX_FILE_NOT_FOUND)
        }
        org.joda.time.DateTime startTime = null;
// Get the list Iterator
        if (gpx_parsed!=null){
            List<Track> tracks = gpx_parsed.getTracks();
            List<TrackSegment> trackSegments;
            if (tracks.size()>0) {
                trackSegments = tracks.get(0).getTrackSegments();
                if (trackSegments.size() > 0) {
                    gpxTrackPoints = trackSegments.get(0).getTrackPoints();
                    // Empty GPX file
                    if(gpxTrackPoints.size()==0) {
//                        display_error_and_quit(EMPTY_GPX_FILE);
                        Log.d("Error: ", String.valueOf(gpxTrackPoints.size()));
                    }
                    gpxTrackPointsIterator = gpxTrackPoints.listIterator();
                    startTime = gpxTrackPoints.get(0).getTime();
//                    Object end_time = gpxTrackPoints.get(gpxTrackPoints.size()-1).getTime().getMillis();
                }
            }
        }
//
        CSVWriter writer;
//        //Write location data on every frame

        writer = create_csv_file();
        currentGpxPoint = gpxTrackPoints.get(0);
//        org.joda.time.DateTime startTime = start_time;
        while (null != (picture = grab.getNativeFrame())) {
            i++;
//            if(i%2==0)
//                continue;
            System.out.println(picture.getWidth() + "x" + picture.getHeight() + " " + picture.getColor());
//            BufferedImage bufferedImage = AWTUtil.toBufferedImage(picture);
//            ImageIO.write(bufferedImage, "png", new File("frame"+i+".png"));
            //for Android (jcodec-android)
            Bitmap bitmap = AndroidUtil.toBitmap(picture);
//            bitmap = flipIMage(bitmap);
//            bitmap = Bitmap.createScaledBitmap(bitmap, 1080, 1920, false);
            bitmap = apply_location(bitmap,i,startTime,writer);
            //Store in arraylist
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, new FileOutputStream(getExternalFilesDir(null)+ File.separator+ "frame_"+i+".png"));
        }
        writer.close();

    //Make video again


        FileChannelWrapper out = null;
        path = getExternalFilesDir(null) +
                File.separator + "GPS_Video_Logger" + File.separator + filename +"final_result.mp4";
        File files = new File(path);
        try { out = NIOUtils.writableFileChannel(files.getAbsolutePath());
        AndroidSequenceEncoder encoder = new AndroidSequenceEncoder(out, Rational.R(30, 1));
// GOP size will be supported in 0.2
// enc.getEncoder().setKeyInterval(25);
        for (int frameNum=1;frameNum<i;frameNum++) {
            Bitmap bitmap = BitmapFactory.decodeFile(getExternalFilesDir(null) +
                    File.separator+"frame_"+ String.valueOf(frameNum)+".png");
            encoder.encodeImage(bitmap);
        }
        encoder.finish();
    } finally {
        NIOUtils.closeQuietly(out);
    }

        //Get data using OCR and write to CSV
        path = getExternalFilesDir(null) +
                File.separator + "GPS_Video_Logger" + File.separator + filename +"final_result.mp4";
        Log.i("Path:", path);
        file = new File(path);
        grab = null;
        try {
            grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(file));
        } catch (JCodecException e) {
            e.printStackTrace();
        }

        i=0;int c=0;
        // Read the data
        FileReader filereader = new FileReader(getExternalFilesDir(null) +
                File.separator + "GPS_Video_Logger" + File.separator + filename + "after_Processing.csv");

        // create csvReader object and skip first Line
        CSVReader csvReader = new CSVReaderBuilder(filereader)
                .withSkipLines(1)
                .build();
        List<String[]> allData = csvReader.readAll();
        writer = create_csv_file();
        for(int key = 0;key <=9;key++)
        {accuracy.put(Integer.valueOf(key), Arrays.asList(0,0));}
//Measure text width and test
        Paint p = new Paint();
        //Change font size and gap
        p.setTypeface(Typeface.create("Arial",Typeface.BOLD));
        p.setTextSize(60);
        Paint.FontMetrics fm = new Paint.FontMetrics();
        p.setColor(Color.BLACK);
        p.getFontMetrics(fm);
        int margin = 20;
        crop_height =  (15 + fm.bottom + margin- (30 + fm.top - margin))+10;
        crop_width = 5 + p.measureText("000.000000000000, 000.000000000000") + margin - (5 - margin)+ 100;
        while (null != (picture = grab.getNativeFrame())) {
            i++;
            System.out.println(picture.getWidth() + "x" + picture.getHeight() + " " + picture.getColor());
            //for Android (jcodec-android)
            Bitmap bitmap = AndroidUtil.toBitmap(picture);
                //Find number of persons
        cropBitmap = Utils.processBitmap(bitmap, TF_OD_API_INPUT_SIZE);
//        int count=0;
//        if(i==1) {
//            initBox();
//        }
//        if(cropBitmap!=null) {
//            List<Classifier.Recognition> results = detector.recognizeImage(cropBitmap);
//            count = handleResult(cropBitmap, results);
//        }
            //Crop just the region of Interest
            bitmap = Bitmap.createBitmap(bitmap,0,0,(int) crop_width,(int) crop_height);
            String extracted_text= get_text_from_image(bitmap);
            convert_to_csv(i, writer,extracted_text, allData);
//            convert_to_csv_persons(i, writer,extracted_text, count,allData);
            if(i==allData.size()-1)
            {
                break;
            }
        }
        writer.close();
        for(int key = 0;key <=9;key++)
        {List l = accuracy.get(key);
         Log.d("Info: ", String.valueOf(key));
            Log.d("Info: ", String.valueOf(l.get(0)));
            Log.d("Info: ", String.valueOf(l.get(1)));}
//        File Path = new File(getExternalFilesDir(null) +
//                File.separator + "Frame_2.jpeg");
//        sourceBitmap = BitmapFactory.decodeFile(getExternalFilesDir(null) +
//                File.separator  + "Frame_2.jpeg");
//
//        //Utils.getBitmapFromAsset(MainActivity.this, "kite.jpg");
//
//        cropBitmap = Utils.processBitmap(sourceBitmap, TF_OD_API_INPUT_SIZE);
//        initBox();
//        if(cropBitmap!=null) {
//            List<Classifier.Recognition> results = detector.recognizeImage(cropBitmap);
//            int count = handleResult(cropBitmap, results);
//        }
    }

    private void initBox() {
        previewHeight = TF_OD_API_INPUT_SIZE;
        previewWidth = TF_OD_API_INPUT_SIZE;
        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        TF_OD_API_INPUT_SIZE, TF_OD_API_INPUT_SIZE,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

//        tracker = new MultiBoxTracker(this);
//        trackingOverlay = findViewById(R.id.tracking_overlay);
//        trackingOverlay.addCallback(
//                canvas -> tracker.draw(canvas));
//
//        tracker.setFrameConfiguration(TF_OD_API_INPUT_SIZE, TF_OD_API_INPUT_SIZE, sensorOrientation);

        try {
            detector =
                    YoloV4Classifier.create(
                            getAssets(),
                            TF_OD_API_MODEL_FILE,
                            TF_OD_API_LABELS_FILE,
                            TF_OD_API_IS_QUANTIZED);
        } catch (final IOException e) {
            e.printStackTrace();
            Log.e("ERROR", "initBox: Exception initializing classifier!");
            Toast toast =
                    Toast.makeText(
                            getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }
    }

    private int handleResult(Bitmap bitmap, List<Classifier.Recognition> results) {
        final Canvas canvas = new Canvas(bitmap);
        int count = 0;
        final Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.0f);

        final List<Classifier.Recognition> mappedRecognitions =
                new LinkedList<Classifier.Recognition>();

        for (final Classifier.Recognition result : results) {
            final RectF location = result.getLocation();
            if (location != null && result.getConfidence() >= MINIMUM_CONFIDENCE_TF_OD_API) {
                canvas.drawRect(location, paint);
//                cropToFrameTransform.mapRect(location);
//
//                result.setLocation(location);
//                mappedRecognitions.add(result);
                //Count if title is person
                if((result.getTitle()).equals("person"))
                {
                    count++;
                }
                //Print count
            }
            Log.i("INFO", "handleResult: "+count);
        }
//        tracker.trackResults(mappedRecognitions, new Random().nextInt());
//        trackingOverlay.postInvalidate();
//        imageView.setImageBitmap(bitmap);
        return count;

    }

    private void stop_rec_and_release() throws IOException {

        // stop recording and release camera
        mediaRecorder.stop();  // stop the recording
        Log.d("Rec","Mediarecorder stopped");


        //Close GPX File
        finish_gpx_file();
        Log.d("Rec","File Closed");

        releaseMediaRecorder(); // release the MediaRecorder object
        Log.d("Rec","MediaRecorder released");
        extract_images_store();
        mCamera.lock();         // take camera access back from MediaRecorder
        Log.d("Rec","Take camera access back from MediaRecorder");

        // Unlock screen rotation
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

        // Inform the user that recording has stopped
        recordButton.setBackgroundResource(R.drawable.rec);
        isRecording = false;
        forceRec = false;
        setGPSFixSpinner(); // Again display GPS fix status

    }


    // Location listener
    //    https://stackoverflow.com/questions/2021176/how-can-i-check-the-current-status-of-the-gps-receiver
    private final LocationListener mlocListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null) return;
            mLastLocation = location;
            mLastLocationMillis = SystemClock.elapsedRealtime();
            if (isRecording && (hasGPSFix || forceRec)){
                if (!gotFirstFix){
                    // When the recording was forced by user, the first location after fix is obtained is
                    // saved as location at start time also.
                    // This ensures gpx and video file are in sync
                    update_location_gpx(location, currentRecordingStartTime);
                    gotFirstFix = true;
                }
               update_location_gpx(location, System.currentTimeMillis());
            }
        }

        @Override
        public void onStatusChanged(String s, int i, Bundle bundle) {

        }

        @Override
        public void onProviderEnabled(String s) {

        }

        @Override
        public void onProviderDisabled(String s) {

        }
    };


    // GpsStatus Listener
    //    https://stackoverflow.com/questions/2021176/how-can-i-check-the-current-status-of-the-gps-receiver
    private final GpsStatus.Listener mGpsStatusListener = new GpsStatus.Listener() {
        @SuppressLint("MissingPermission")
        @Override
        public void onGpsStatusChanged(int event) {
            switch (event) {
                case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
                    if (mLastLocation != null)
                    {
                        if((SystemClock.elapsedRealtime() - mLastLocationMillis) < 5000)
                        {
                            if (!hasGPSFix)
                                Log.i("GPS","Fix Acquired");
                            setGPSFix(true);
                        }
                        else
                        {
                            if (hasGPSFix)
                            {
                                Log.i("GPS","Fix Lost (expired)");
                                mlocManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, FREQUENCY, 0, mlocListener);
                            }
                            setGPSFix(false);
                        }
                    }
                    break;
                case GpsStatus.GPS_EVENT_FIRST_FIX:
                    Log.i("GPS", "First Fix/ Refix");
                    setGPSFix(true);
                    break;
                case GpsStatus.GPS_EVENT_STARTED:
                    Log.i("GPS", "Started!");
                    break;
                case GpsStatus.GPS_EVENT_STOPPED:
                    Log.i("GPS", "Stopped");
                    break;
            }
        }
    };

    // GnssStatus Listener
    private final GnssStatus.Callback mGnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onStarted() {
            super.onStarted();
            Log.i("GPS", "Started!");
        }

        @Override
        public void onStopped() {
            super.onStopped();
            Log.i("GPS", "Stopped");
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            super.onFirstFix(ttffMillis);
            Log.i("GPS", "First Fix/ Refix");
            setGPSFix(true);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            super.onSatelliteStatusChanged(status);
            if (mLastLocation != null)
            {
                if((SystemClock.elapsedRealtime() - mLastLocationMillis) < 5000)
                {
                    if (!hasGPSFix)
                        Log.i("GPS","Fix Acquired");
                    setGPSFix(true);
                }
                else
                {
                    if (hasGPSFix)
                    {
                        Log.i("GPS","Fix Lost (expired)");
                        mlocManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, FREQUENCY, 0, mlocListener);
                    }
                    setGPSFix(false);
                }
            }
        }
    };


    // Utilty function that normalizes time based on mode (video/time lapse)
    private long normalize_time_based_on_mode(long current_time){
        if (mode==TIMELAPSE_MODE){
            // Note : The time we store in GPX file is with respect to the final video
            // So in the case of a timelapse video at 2 FPS and PLAYBACK_FRAME_RATE = 16, 8 seconds of travel time would
            // be 1 second in final video. So we normalize the actual time to accomodate this before saving to GPX file
            return (currentRecordingStartTime + (current_time-currentRecordingStartTime)/(PLAYBACK_FRAME_RATE/TIME_LAPSE_FPS));
        }

        // For VIDEO_MODE return time as is
        return current_time;
    }



    // Set mode :- VIDEO_MODE or TIMELAPSE_MODE mode
    private void set_mode(int new_mode){
        switch (new_mode){
            case VIDEO_MODE:
                mode = VIDEO_MODE;
                modeButton.setBackgroundResource(R.drawable.video_mode);
                setPreferredQuality();
                updateLocationFreq(FREQUENCY);
                Log.d("Mode","VIDEO_MODE");
                break;
            case TIMELAPSE_MODE:
                // If 2 FPS at 16 PLAYBACK_FRAME_RATE, 8 seconds of travel correspond to 1 second of footage
                // If LOCATION_INTERVAL is 2, that means we need location at 2 second intervals in
                // final footage which would translate to getting 1 location point in 16 seconds when the
                // actual recording is happening

                mode = TIMELAPSE_MODE;
                // These values depend on camcorder profile
                CamcorderProfile profile = getTimeLapseCamcorderProfile();
                TIME_LAPSE_FPS = (long)(profile.videoFrameRate/6.0);
                PLAYBACK_FRAME_RATE = profile.videoFrameRate;
                modeButton.setBackgroundResource(R.drawable.timelapse_mode);
                setPreferredQuality();
                updateLocationFreq(FREQUENCY);
                Log.d("Mode","TIMELAPSE_MODE");
                Log.d("TimeLapse Factor", Long.toString(PLAYBACK_FRAME_RATE/TIME_LAPSE_FPS));
                break;
        }
    }




    //Initialization functions

    private void initialize_camera(int cameraId){
        // Create an instance of Camera
        mCamera = getCameraInstance(cameraId);

        //Setting orientation of camera
        currentCameraOrientationResult = getCameraDisplayOrientation(this, cameraId);
        mCamera.setDisplayOrientation(currentCameraOrientationResult);

        // Create our Preview view and set it as the content of our activity.
        ConstraintLayout preview = (ConstraintLayout) findViewById(R.id.camera_preview);
        mPreview = new CameraPreview(this, mCamera);
        preview.addView(mPreview);

        Log.d("Camera","Initialized");
    }



    private void initialize_app_folder(){
        //Check for app folder
        File folder = new File(getExternalFilesDir(null) +
                File.separator + "GPS_Video_Logger");
        if (!folder.exists()) {
            //Handle error in making folder
            boolean success = folder.mkdir();
            Log.d("Folder-Creation", Boolean.toString(success));
        }
    }



    private void initialize_location(){
        //Initialising location manager
        mlocManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);


        //Setting up location requests
        setGPSLocationUpdates(true);
        setGPSFixSpinner();
    }


    public void setGPSLocationUpdates(boolean state) {

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        {

            if (!state && !isRecording && isGPSLocationUpdatesActive) {
                Log.d("GPS", "Stopping");

                // From Android 12 GPSStatusListener does not work
                // GNSSStatusCallback was added only in API 24
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
                    mlocManager.unregisterGnssStatusCallback(mGnssStatusCallback);
                }
                else{
                    mlocManager.removeGpsStatusListener(mGpsStatusListener);
                }

                mlocManager.removeUpdates(mlocListener);
                isGPSLocationUpdatesActive = false;
            } else if (state && !isGPSLocationUpdatesActive) {

                // From Android 12 GPSStatusListener does not work
                // GNSSStatusCallback was added only in API 24
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
                    mlocManager.registerGnssStatusCallback(this.getMainExecutor(),mGnssStatusCallback);
                }
                else{
                    mlocManager.addGpsStatusListener(mGpsStatusListener);
                }

                mlocManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, FREQUENCY, 0, mlocListener);
                isGPSLocationUpdatesActive = true;
                Log.d("GPS", "Started");
            }

        }
    }


    private void updateLocationFreq(long frequency){
        if (isGPSLocationUpdatesActive
                && (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED))
        {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
                    mlocManager.unregisterGnssStatusCallback(mGnssStatusCallback);
            }
            else{
                mlocManager.removeGpsStatusListener(mGpsStatusListener);
            }

            mlocManager.removeUpdates(mlocListener);


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
                mlocManager.registerGnssStatusCallback(this.getMainExecutor(),mGnssStatusCallback);
            }
            else{
                mlocManager.addGpsStatusListener(mGpsStatusListener);
            }

            mlocManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, frequency, 0, mlocListener);
        }
    }


    // Update the spinner based on gps fix
    private void setGPSFixSpinner(){

        if (hasGPSFix) {
            mProgressBar.clearAnimation();
            mProgressBar.setVisibility(View.INVISIBLE);
            tickView.setVisibility(View.VISIBLE);
        } else {
            mProgressBar.startAnimation(
                    AnimationUtils.loadAnimation(this, R.anim.rotate) );
            mProgressBar.setVisibility(View.VISIBLE);
            tickView.setVisibility(View.INVISIBLE);
        }
    }

    private void setGPSFix(boolean state){
        hasGPSFix = state;
        setGPSFixSpinner();
    }








    // Configure MediaRecorder
    // https://developer.android.com/guide/topics/media/camera#configuring-mediarecorder
    private boolean prepareVideoRecorder(){

        mediaRecorder = new MediaRecorder();
        // Step 1: Unlock and set camera to MediaRecorder
        mCamera.unlock();
        mediaRecorder.setCamera(mCamera);

        mediaRecorder.setOrientationHint(currentCameraOrientationResult);

        // Todo : Possible bug because hardcoded 90 in CameraPreview
        // When front camera and vertical, video recorded is upside down when orientation is 90
        // So we manually change it to 270 for that case
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(currentCameraId, info);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && currentCameraOrientationResult==90){
            Log.d("Cam", "Front camera corner case triggered");
            mediaRecorder.setOrientationHint(270);
        }


        // Step 2: Set sources and camcoder profile based on mode

        if(mode == VIDEO_MODE){
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            CamcorderProfile profile = getVideoCamcorderProfile();
            profile.fileFormat = VIDEO_FORMAT;
            mediaRecorder.setProfile(profile);
        }


        if (mode==TIMELAPSE_MODE){
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

            CamcorderProfile profile = getTimeLapseCamcorderProfile();
            profile.fileFormat = VIDEO_FORMAT;
            mediaRecorder.setProfile(profile);

            // When setting Time lapse profile, for certain qualities setAudioEncoder has to be called separately
            // otherwise exception occurs. At the same time for the other qualities setProfile sets audio encoder and calling
            // setAudioEncoder again causes exception. This try catch block is to handle this unique case.
            try {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
            }
            catch (Exception e){
                // This exception is raised for most qualities, in which setProfile sets audio encoder
                Log.d("MediaRecorder", "SetAudioEncoder Exception: audio encoder has already been set ");
            }


            Log.d("Timelapse", Integer.toString(profile.videoBitRate));
            Log.d("Timelapse", Integer.toString(profile.videoFrameRate));

            // Todo : Let the user fully customize capture rate, playback rate

            mediaRecorder.setCaptureRate(profile.videoFrameRate/6.0f);
            mediaRecorder.setVideoFrameRate(profile.videoFrameRate);
            mediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        }


        // Step 4: Set output file
        mediaRecorder.setOutputFile(getExternalFilesDir(null) +
                File.separator + "GPS_Video_Logger" + File.separator + filename +".mp4");

        // Step 5: Set the preview output
        mediaRecorder.setPreviewDisplay(mPreview.getHolder().getSurface());

        // Step 6: Prepare configured MediaRecorder
        try {
            mediaRecorder.prepare();
        } catch (IllegalStateException e) {
            Log.d("Cam", "IllegalStateException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        } catch (IOException e) {
            Log.d("Cam", "IOException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        }
        return true;
    }






    // Camera Utility functions

    private int getBackCameraID(){
        int cameraId = -1;
        int numberOfCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                Log.d("Cam", "Back Camera found");
                cameraId = i;
                break;
            }
        }
        return cameraId;
    }

    private int getFrontCameraID(){
        int cameraId = -1;
        int numberOfCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                Log.d("Cam", "Front Camera found");
                cameraId = i;
                break;
            }
        }
        return cameraId;
    }

    public static Camera getCameraInstance(int cameraId){
        Camera c = null;
        try {
            c = Camera.open(cameraId); // attempt to get a Camera instance
            Camera.Parameters params = c.getParameters();
            params.setRecordingHint(true);

            /// Continuous focus is only  available for back camera
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK){
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }

            c.setParameters(params);
        }
        catch (Exception e){
            // Camera is not available (in use or does not exist)
            e.printStackTrace();
        }
        return c; // returns null if camera is unavailable
    }


    private void releaseMediaRecorder(){
        if (mediaRecorder != null) {
            mediaRecorder.reset();   // clear recorder configuration
            mediaRecorder.release(); // release the recorder object
            mediaRecorder = null;
            mCamera.lock();           // lock camera for later use
        }
    }

    private void stopPreview(){
        if (mCamera != null)
            mCamera.stopPreview();
    }

    private void releaseCamera(){
        if (mCamera != null){
            mCamera.stopPreview();
            mCamera.release();        // release the camera for other applications
            mCamera = null;
        }
    }


    // Utitliy function to get camera orientation based on device orientation
    // https://developer.android.com/reference/android/hardware/Camera#setDisplayOrientation
    public static int getCameraDisplayOrientation(Activity activity,
                                                  int cameraId) {
        Camera.CameraInfo info =
                new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = activity.getWindowManager().getDefaultDisplay()
                .getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }


    // Utilty function to lock orientation
    // Ref ; https://stackoverflow.com/a/14565436/12306553
    public static void lockOrientation(Activity activity) {
        Display display = ((WindowManager) activity.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int rotation = display.getRotation();
        int tempOrientation = activity.getResources().getConfiguration().orientation;
        int orientation = 0;
        switch(tempOrientation)
        {
            case Configuration.ORIENTATION_LANDSCAPE:
                if(rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_90)
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                else
                    orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                break;
            case Configuration.ORIENTATION_PORTRAIT:
                if(rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_270)
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                else
                    orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        }
        activity.setRequestedOrientation(orientation);
    }

    // Utility function which returns appropriate TimeLapse Profile
    private CamcorderProfile getTimeLapseCamcorderProfile(){
        CamcorderProfile profile;
        //Checking if the profile is available
        if(CamcorderProfile.hasProfile(TIME_LAPSE_QUALITY) && currentCameraId == getBackCameraID()){
            // Front camera doesn't support all available qualities
            profile =  CamcorderProfile.get(TIME_LAPSE_QUALITY);
        }
        else{
            profile = CamcorderProfile.get(currentCameraId, CamcorderProfile.QUALITY_TIME_LAPSE_HIGH);
            Log.d("Cam", "High Quality Time Lapse selected");
        }
        return profile;
    }

    // Utility function which returns appropriate Video Profile
    private CamcorderProfile getVideoCamcorderProfile(){
        CamcorderProfile profile;
        //Checking if the profile is available
        if(CamcorderProfile.hasProfile(VIDEO_QUALITY) && currentCameraId == getBackCameraID()){
            // Front camera doesn't support all available qualities
            profile =  CamcorderProfile.get(VIDEO_QUALITY);
        }
        else{
            profile = CamcorderProfile.get(currentCameraId, CamcorderProfile.QUALITY_HIGH);
            Log.d("Cam", "High Quality Video selected");
        }
        return profile;
    }



    // Utiltiy functions to set, change quality

    // Set quality based on mode
    private void setPreferredQuality(){
        // Loads prefered quality from shared preference if present based on current mode
        // Else selects first one from available

        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);

        if(mode  == VIDEO_MODE){
            String pref_quality = sharedPref.getString("Quality_Video", (String) availableVideoQualities.keySet().toArray()[0]);
            VIDEO_QUALITY = availableVideoQualities.get(pref_quality);
            quality_text.setText(pref_quality);
        }
        else if (mode == TIMELAPSE_MODE){
            String pref_quality = sharedPref.getString("Quality_TimeLapse", (String) availableTimeLapseQualities.keySet().toArray()[0]);
            TIME_LAPSE_QUALITY = availableTimeLapseQualities.get(pref_quality);
            quality_text.setText(pref_quality);
        }
    }

    // Updates quality corresponding to current mode to given value
    private void updatePreferredQuality(String pref_quality){

        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();

        if (mode == VIDEO_MODE){
            // Save settings to shared preference
            editor.putString("Quality_Video", pref_quality);
            editor.apply();
            // Change setting and update UI
            VIDEO_QUALITY = availableVideoQualities.get(pref_quality);
            quality_text.setText(pref_quality);
        }
        else if (mode ==TIMELAPSE_MODE){
            // Save settings to shared preference
            editor.putString("Quality_TimeLapse", pref_quality);
            editor.apply();
            // Change setting and update UI
            TIME_LAPSE_QUALITY = availableTimeLapseQualities.get(pref_quality);
            quality_text.setText(pref_quality);
        }
    }

    @NonNull
    private HashMap<String, Integer> getAvailableVideoQualities() {
        // Iterate over all camcorder profiles and returns map of supported profiles
        HashMap<String, Integer> camcorderVideoProfiles = new HashMap<>();
        camcorderVideoProfiles.put("144p", CamcorderProfile.QUALITY_QCIF);
        camcorderVideoProfiles.put("240p", CamcorderProfile.QUALITY_QVGA);
        camcorderVideoProfiles.put("480p", CamcorderProfile.QUALITY_480P);
        camcorderVideoProfiles.put("720p", CamcorderProfile.QUALITY_720P);
        camcorderVideoProfiles.put("1080p", CamcorderProfile.QUALITY_1080P);
        camcorderVideoProfiles.put("2160p", CamcorderProfile.QUALITY_2160P);

        HashMap<String, Integer> resultMap = new HashMap<>();

        for (HashMap.Entry<String, Integer> entry : camcorderVideoProfiles.entrySet()){
            if(CamcorderProfile.hasProfile(entry.getValue()))
                resultMap.put(entry.getKey(), entry.getValue());
        }
        return resultMap;
    }

    @NonNull
    private HashMap<String, Integer> getAvailableTimeLapseQualities() {
        // Iterate over all camcorder profiles and returns map of supported profiles
        HashMap<String, Integer> camcorderTimeLapseProfiles = new HashMap<>();
        camcorderTimeLapseProfiles.put("144p", CamcorderProfile.QUALITY_TIME_LAPSE_QCIF);
        camcorderTimeLapseProfiles.put("240p", CamcorderProfile.QUALITY_TIME_LAPSE_QVGA);
        camcorderTimeLapseProfiles.put("480p", CamcorderProfile.QUALITY_TIME_LAPSE_480P);
        camcorderTimeLapseProfiles.put("720p", CamcorderProfile.QUALITY_TIME_LAPSE_720P);
        camcorderTimeLapseProfiles.put("1080p", CamcorderProfile.QUALITY_TIME_LAPSE_1080P);
        camcorderTimeLapseProfiles.put("2160p", CamcorderProfile.QUALITY_TIME_LAPSE_2160P);

        HashMap<String, Integer> resultMap = new HashMap<>();

        for (HashMap.Entry<String, Integer> entry : camcorderTimeLapseProfiles.entrySet()){
            if(CamcorderProfile.hasProfile(entry.getValue()))
                resultMap.put(entry.getKey(), entry.getValue());
        }
        return resultMap;
    }





    //GPX file handling functions

    // Create gpx file
    private void  create_gpx_file(String filename){
        Log.d("oss","Creating file");
        try {
            fos = new FileOutputStream(new File( getExternalFilesDir(null) +
                    File.separator + "GPS_Video_Logger", filename));
            Log.d("oss","Opened file");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        serializer = Xml.newSerializer();

        try {
            serializer.setOutput(fos, "UTF-8");
            serializer.startDocument(null, Boolean.TRUE);

            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, "gpx");
            serializer.attribute(null, "version","1.0");
            serializer.attribute(null,"xmlns:xsi","http://www.w3.org/2001/XMLSchema-instance");

            serializer.startTag(null,"trk");

            serializer.startTag(null,"name");
            serializer.text("emulate");
            serializer.endTag(null,"name");

            serializer.startTag(null,"trkseg");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    // Update provided location data into gpx file
    private void update_location_gpx(Location location, long time_in_ms){

        // Location need only be saved less frequently in time lapse mode
        // Ideally we should save only points for every one sec in final video
        // (PLAYBACK_FRAME_RATE/TIME_LAPSE_FPS) seconds in realtime would be one second in final video

        long normalized_time = normalize_time_based_on_mode(time_in_ms);

        if (mode == TIMELAPSE_MODE){
            long preferred_time_gap = (PLAYBACK_FRAME_RATE/TIME_LAPSE_FPS)*1000; // Convert to Millisec
//            Log.d("Timelapse", "Time gap in saving " + Long.toString(preferred_time_gap));

            // Time is compared in realtime
            if ( (time_in_ms- prevLocSavedTime) < preferred_time_gap ){
                return;
            }
        }

        try {
            serializer.startTag(null, "trkpt");
            serializer.attribute(null,"lat", Double.toString(location.getLatitude()));
            serializer.attribute(null,"lon", Double.toString(location.getLongitude()));

            serializer.startTag(null,"ele");
            serializer.text(Double.toString(location.getAltitude()));
            serializer.endTag(null,"ele");
            serializer.startTag(null,"time");

            String time = sdf.format(normalized_time); // For video mode it would same

            serializer.text(time);
            serializer.endTag(null,"time");

            serializer.endTag(null, "trkpt");

            // Update prev saved time. Real time is stored here for future comparison
            prevLocSavedTime = time_in_ms;

        } catch (IOException e) {
            e.printStackTrace();
        }
    }    
    
    // Closing the gpx file
    private void finish_gpx_file(){
        try {

            if(serializer!=null){
                serializer.endTag(null,"trkseg");
                serializer.endTag(null,"trk");
                serializer.endTag(null,"gpx");

                serializer.endDocument();
                serializer.flush();

                fos.close();
                serializer = null;
                Log.d("oss","Closed file");
            }


        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    //Utility function to show alert messages
    private void display_alert(int msgCode){

        String msg = "";
        switch(msgCode){
            case INSTRUCTIONS:
                msg = "Video format is mp4" +"\n"+
                        "GPS track is saved in GPX file format" + "\n" +
                        "Both files would have the same name" + "\n" +
                        "The separate files can be found in the app folder at Android/data/" + "\n" +
                        "Swipe to delete video file" + "\n" +
                        "Long press to rename videos" + "\n" +
                        "Video Mode and Time Lapse Mode available";
                break;
            case FIX_PENDING:
                msg = "Please wait for the GPS to get a fix on your location." + "\n" +
                        "GPS Fix can be delayed if you are indoors or surrounded by tall building.";
                break;
            case FIX_INFO:
                msg = "GPS Fix Status" +"\n" +
                        "A GPS Fix means your device is in view of enough satellites to get a proper lock on your position" + "\n" +
                        "Even while recording, locations are saved only when a fix is present";
                break;
            case EXPERIMENTAL_TIME_LAPSE_MODE:
                msg = "Time Lapse Mode - Experimental" + "\n" +
                        "Time lapse recording and playback is heavily dependant on hardware capabiltiy" + "\n"+
                        "Accuracy can not be guaranteed in final output";
                break;
        }

        // No corresponding GPX file. Ensure same name, Show alert before quit
        // Setting Dialog Title
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(Recording.this,R.style.DialogTheme);
        alertBuilder.setTitle("GPS Video Logger "+ getResources().getString(R.string.ver_code));

        // Setting Dialog Message
        alertBuilder.setMessage(msg);

        AlertDialog dialog = alertBuilder.create();
        dialog.show();
    }


    // Utility function for checking if location is enabled

    private boolean isLocationEnabled(){
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Logging if altitude data is supported by GPS on phone
        Log.d("Altitude available", Boolean.toString(locationManager.getProvider(LocationManager.GPS_PROVIDER).supportsAltitude()));

        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

}
