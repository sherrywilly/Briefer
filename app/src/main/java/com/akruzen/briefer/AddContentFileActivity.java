package com.akruzen.briefer;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.documentfile.provider.DocumentFile;
import androidx.room.Room;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.akruzen.briefer.Constants.Constants;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import com.akruzen.briefer.db.AppDatabase;
import com.akruzen.briefer.db.Topic;
import com.akruzen.briefer.db.TopicDao;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AddContentFileActivity extends AppCompatActivity {

    Uri selectedFileUri;
    DocumentFile documentFile;
    ContentResolver contentResolver;
    InputStream inputStream;
    PdfReader reader;
    TinyDB tinyDB;
    String fileExtension, fileName;
    TextInputEditText questionFileTextInputEditText;
    TextView fileNameTV, fileTypeTV, pagesTV, totalCharsTV, splittingTV;
    ConstraintLayout constraintLayout;
    MaterialButton fileTypeButton;
    MaterialCardView splittingCardView;
    String extractedContent = "";

    TopicDao topicDao;
    AppDatabase db;

    public void saveFABClicked(View view) {
        // Save the file if content is available
        if (fileName != null && !extractedContent.isEmpty()) {
            saveTopicToDatabase(fileName, extractedContent); // Save the topic with file name and content
//            Toast.makeText(this, "Topic saved", Toast.LENGTH_SHORT).show();

        } else {
            Toast.makeText(this, "No content to save", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveTopicToDatabase(String title, String content) {
        try {
            String currDateTime = new SimpleDateFormat("yyyyMMddHHmmss", Locale.UK).format(new Date());
            int appVersion;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0));
                appVersion = (int) pInfo.getLongVersionCode();
            } else {
                appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            }
            int sdkVersion = Build.VERSION.SDK_INT;

            Topic topic = new Topic(
                    title, content, Topic.TYPE_PLAIN_TEXT, currDateTime, appVersion, sdkVersion
            );
            topicDao.insertTopic(topic);

            Log.i("Database", "Topic saved with title: " + title);
            finish(); // Close the activity after saving
        } catch (PackageManager.NameNotFoundException e) {
            Toast.makeText(this, "Error saving topic to database", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_content_file);

        // Find view by id
        questionFileTextInputEditText = findViewById(R.id.questionFileTextInput);
        fileTypeButton = findViewById(R.id.fileTypeButton);
        fileNameTV = findViewById(R.id.fileNameTV);
        fileTypeTV = findViewById(R.id.fileTypeTV);
        pagesTV = findViewById(R.id.pagesTV);
        totalCharsTV = findViewById(R.id.totalCharsTV);
        splittingTV = findViewById(R.id.splittingTV);
        constraintLayout = findViewById(R.id.addContentFileActivityConstraintLayout);
        splittingCardView = findViewById(R.id.splittingCardView);

        // Initialize TinyDB and Room database
        db = Room.databaseBuilder(this, AppDatabase.class, "TopicDatabase").allowMainThreadQueries().build();
        topicDao = db.topicDao();
        tinyDB = new TinyDB(this);


        // Method Calls
        getIntentExtras();
        setTexts();
    }

    private void getIntentExtras() {
        selectedFileUri = Objects.requireNonNull(getIntent().getExtras()).getParcelable(Constants.FILE_INTENT_EXTRA);
        fileExtension = Objects.requireNonNull(getIntent().getExtras()).getString(Constants.FILE_EXTENSION_INTENT_EXTRA);
    }

    private void setTexts() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setView(R.layout.progress_dialog);
        builder.setCancelable(false);
        builder.setNegativeButton("Dismiss", (dialog, which) -> {
            dialog.dismiss();
            executor.shutdownNow(); // Stop the thread when tapped on dismiss
        });
        AlertDialog dialog = builder.create();
        handler.post(dialog::show);

        executor.execute(() -> {
            try {
                StringBuilder extractedText = new StringBuilder();
                reader = setPdfReader();
                int pageCount = reader.getNumberOfPages();
                for (int i = 0; i < pageCount; i++) {
                    extractedText.append(PdfTextExtractor.getTextFromPage(reader, i + 1).trim()).append("\n");
                }
                extractedContent = extractedText.toString().trim();
                int charCount = extractedContent.length();
                documentFile = DocumentFile.fromSingleUri(this, selectedFileUri);
                fileName = documentFile.getName();

                runOnUiThread(() -> {
                    fileTypeButton.setText(fileExtension);
                    pagesTV.setText("Pages: " + pageCount);
                    totalCharsTV.setText("Total Characters: " + charCount);
                    if (charCount == 0) {
                        Snackbar s = Snackbar.make(constraintLayout, getString(R.string.incorrect_file_warning), Snackbar.LENGTH_INDEFINITE);
                        s.setAction("Got it", view -> s.dismiss());
                        s.setTextMaxLines(6);
                        s.show();
                    }
                    String charLimitStr = tinyDB.getString(Constants.getCharLimitKey());
                    int charLimit = charLimitStr.isEmpty() ? Integer.parseInt(Constants.DEFAULT_CHAR_LIMIT) : Integer.parseInt(charLimitStr);
                    if (charCount > charLimit) {
                        splittingTV.setText("Splitting: " + (int)(Math.ceil((double)charCount / charLimit)) + " parts");
                        splittingCardView.setVisibility(View.VISIBLE);
                    } else {
                        splittingTV.setText("Splitting: No splitting required");
                    }
                    questionFileTextInputEditText.setText(fileName);
                    fileNameTV.setText("Filename: " + fileName);
                    fileTypeTV.setText("File Type: " + documentFile.getType());
                });
                runOnUiThread(dialog::dismiss); // Dismiss the dialog upon completion
            } catch (NullPointerException e) {
                runOnUiThread(() -> Toast.makeText(this, "One or more attributes of file are null", Toast.LENGTH_SHORT).show());
                e.printStackTrace();
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "Error reading the PDF", Toast.LENGTH_SHORT).show());
                throw new RuntimeException(e);
            }
        });
    }

    private PdfReader setPdfReader() throws IOException {
        try {
            contentResolver = getContentResolver();
            inputStream = contentResolver.openInputStream(selectedFileUri);
            if (inputStream != null) {
                return new PdfReader(inputStream);
            } else {
                Toast.makeText(this, "Could not open the document to read contents", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        }
        return null;
    }
}
