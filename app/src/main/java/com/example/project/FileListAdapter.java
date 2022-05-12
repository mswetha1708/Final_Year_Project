package com.example.project;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.example.project.detection.customview.OverlayView;
import com.example.project.detection.env.ImageUtils;
import com.example.project.detection.env.Utils;
import com.example.project.detection.tflite.Classifier;
import com.example.project.detection.tflite.YoloV4Classifier;
import com.example.project.detection.tracking.MultiBoxTracker;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;
import com.opencsv.CSVWriter;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.AndroidUtil;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import static org.bytedeco.opencv.global.opencv_core.finish;

public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.ViewHolder> {

    private ArrayList<String> mDataset;
    Context context;
    private int activity;
    private String fileChosen;



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



    // Provide a reference to the views for each data item
    // Complex data items may need more than one view per item, and
    // you provide access to all the views for a data item in a view holder
    static class ViewHolder extends RecyclerView.ViewHolder {
        // each data item is just a string in this case
        View mView;
        final ImageView mImageView;
        final TextView mTextView;

        ViewHolder(View v) {
            super(v);
            mView = v;
            mImageView = v.findViewById(R.id.item_image);
            mTextView = v.findViewById(R.id.item_name);
        }
    }


    // Provide a suitable constructor (depends on the kind of dataset)
    public FileListAdapter(int act, ArrayList<String> myDataset, Context myContext) {
        mDataset = myDataset;
        context = myContext;
        activity = act;
    }


    // Create new views (invoked by the layout manager)
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent,
                                                                              int viewType) {
        // create a new view
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.file_list_view, parent, false);

        return new ViewHolder(view);
    }



    // Replace the contents of a view (invoked by the layout manager)
    @Override
    public void onBindViewHolder(final ViewHolder holder, final int position) {
        // - get element from your dataset at this position
        // - replace the contents of the view with that element

        // Check if file exists - Sometimes race condition causes file to be dalyed in being created
        String filePath = "";
        if(activity==1 || activity==4) {
            filePath = context.getExternalFilesDir(null)
                    + File.separator + "GPS_Video_Logger" + File.separator + mDataset.get(position) + ".mp4";
        }
        else {
            filePath = context.getExternalFilesDir(null)
                    + File.separator + "GPS_Video_Logger" + File.separator + mDataset.get(position) + ".csv";
        }

        if(!(new File(filePath)).exists())
            return;


        holder.mTextView.setText(mDataset.get(position));
        if(activity ==1 || activity ==4)
        {
            Bitmap bMap = ThumbnailUtils.createVideoThumbnail(filePath, MediaStore.Video.Thumbnails.MINI_KIND);
            holder.mImageView.setImageBitmap(bMap);
        }
        else
            {
                InputStream in = this.getClass().getResourceAsStream("/res/drawable/csv_file.png");
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                Bitmap bMap = ThumbnailUtils.extractThumbnail(bitmap,50 ,50);
                holder.mImageView.setImageBitmap(bMap);

        }


        //On click launch playback activity with selected file
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d("Fileo", Integer.toString(holder.getAdapterPosition()));
                String selectedFilename = mDataset.get(holder.getAdapterPosition());
                fileChosen = selectedFilename;
                Log.d("Fileo",selectedFilename);
                if(activity ==1 || activity ==4)
                    selectedFilename = selectedFilename + ".mp4";
                else
                    selectedFilename = selectedFilename + ".csv";
                if(activity==4)
                {
                    //Create Output and move to filePicker Results(count Person)
                    //Get frame by frame for selected file
                    String path = context.getExternalFilesDir(null) +
                            File.separator + "GPS_Video_Logger" + File.separator + selectedFilename;
                    Log.i("Path:", path);
                    File file = new File(path);
                    FrameGrab grab = null;
                    try {
                        grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(file));
                    } catch (JCodecException | FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    //Measure text width and test
                    Paint p = new Paint();
                    //Change font size and gap
                    p.setTypeface(Typeface.create("Arial",Typeface.BOLD));
                    p.setTextSize(60);
                    Paint.FontMetrics fm = new Paint.FontMetrics();
                    p.setColor(Color.BLACK);
                    p.getFontMetrics(fm);
                    int margin = 20;
                    float crop_height =  (15 + fm.bottom + margin- (30 + fm.top - margin))+10;
                    float crop_width = 5 + p.measureText("000.000000000000, 000.000000000000") + margin - (5 - margin) + 100;
                    Picture picture = null;
                    int i=0;
                    CSVWriter writer = null;
                    try {
                        writer = create_csv_file();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    while (true) {
                        try {
                            if (!(null != (picture = grab.getNativeFrame()))) break;
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        i++;
                        System.out.println(picture.getWidth() + "x" + picture.getHeight() + " " + picture.getColor());
                        //for Android (jcodec-android)
                        Bitmap bitmap = AndroidUtil.toBitmap(picture);
                        //Find number of persons
                        cropBitmap = Utils.processBitmap(bitmap, TF_OD_API_INPUT_SIZE);
                        int count=0;
                        if(i==1) {
                            initBox();
                        }
                        if(cropBitmap!=null) {
                            List<Classifier.Recognition> results = detector.recognizeImage(cropBitmap);
                            count = handleResult(cropBitmap, results);
                        }
                        //Crop just the region of Interest
                        bitmap = Bitmap.createBitmap(bitmap,0,0,(int) crop_width,(int) crop_height);
                        String extracted_text= get_text_from_image(bitmap);
//            convert_to_csv(i, writer,extracted_text, allData);
                        convert_to_csv_persons(i, writer,extracted_text, count);
//                        if(i==allData.size()-1)
//                        {
//                            break;
//                        }
                    }
                    try {
                        writer.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    //Shift to count persons icon
                    //Open CountPersons
                    fileChosen = fileChosen + "count_persons.csv";
                    selectedFilename = fileChosen;
                }
                File file = new File(context.getExternalFilesDir(null)
                        + File.separator + "GPS_Video_Logger", selectedFilename);
                //if file exist
                if(!file.exists())
                    return;

                Uri uri = FileProvider.getUriForFile(context,
                        context.getApplicationContext().getPackageName() + ".provider",
                        file);
                String mime = context.getContentResolver().getType(uri);
                // Open file with user selected app
                Intent intent = new Intent();
                intent.setAction(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, mime);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(Intent.createChooser(intent, "Open file with"));
            }
        });
        // Rename on long click
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {

                AlertDialog.Builder builder = new AlertDialog.Builder(view.getContext(),R.style.DialogTheme);
                builder.setTitle("Rename Journey");

                // Set up the input
                final EditText input = new EditText(view.getContext());
                // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                input.setTextColor(view.getResources().getColor(R.color.colorText));
                input.setText(mDataset.get(holder.getAdapterPosition()));

                builder.setView(input);

                // Set up the buttons
                builder.setPositiveButton("Confirm", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String m_Text = input.getText().toString();
                        String oldName = mDataset.get(holder.getAdapterPosition());
                        rename_file(oldName, m_Text, holder.getAdapterPosition());
                        notifyItemChanged(holder.getAdapterPosition());
                    }
                });
                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });

                builder.show();
                return true;
            }
        });

    }

    private CSVWriter create_csv_file() throws IOException {
        File csvfile = null;
        // Use OCR and add the details , frame number, coordinates in a csv.
        try {
            csvfile = new File(context.getExternalFilesDir(null) +
                    File.separator + "GPS_Video_Logger" + File.separator + fileChosen +"count_persons.csv");
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
        String[] header = {"Frame_Number","Latitude", "Longitude","PersonCount"};
        // closing writer connection
        writer.writeNext(header);
        return writer;
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
                            context.getAssets(),
                            TF_OD_API_MODEL_FILE,
                            TF_OD_API_LABELS_FILE,
                            TF_OD_API_IS_QUANTIZED);
        } catch (final IOException e) {
            e.printStackTrace();
            Log.e("ERROR", "initBox: Exception initializing classifier!");
            Toast toast =
                    Toast.makeText(
                            context.getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
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

    private String get_text_from_image(Bitmap bitmap)
    {
        TextRecognizer txtRecognizer = new TextRecognizer.Builder(context.getApplicationContext()).build();
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

    private void convert_to_csv_persons(int i, CSVWriter writer, String extracted_text, int count) {

        // Split extract Text when a coma is found
        String [] data = extracted_text.split(",");
        //Data1 find number of digits misclassified for each row.
        //Find the digit misclassified and add the total number of digits misclassified along with accuracy (0-9).
        if(data.length <2) {
            return;
        }
        Log.i("INFO", String.valueOf(i));
        //
        String[] data1 = {String.valueOf(i),data[0], data[1],String.valueOf(count)};
        writer.writeNext(data1);
    }


    private void rename_file(String old_name, String new_name, int position){
        String path = context.getExternalFilesDir(null)
                + File.separator + "GPS_Video_Logger" + File.separator;

        if (new_name.length() > 0){
            File new_mp4_file = new File(path + new_name + ".mp4");
            new File(path + old_name + ".mp4").renameTo(new_mp4_file);

            File new_gpx_file = new File(path + new_name + ".gpx");
            new File(path + old_name + ".gpx").renameTo(new_gpx_file);

            mDataset.set(position,new_name);
        }

    }

    // Return the size of your dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return mDataset.size();
    }
}
