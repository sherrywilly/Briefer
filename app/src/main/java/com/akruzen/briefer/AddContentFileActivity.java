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
import com.google.android.material.textfield.TextInputEditText;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import com.akruzen.briefer.db.AppDatabase;
import com.akruzen.briefer.db.Topic;
import com.akruzen.briefer.db.TopicDao;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AddContentFileActivity extends AppCompatActivity {

    private static final String TAG = "AddContentFileActivity";

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
        if (fileName != null && !extractedContent.isEmpty()) {
            String title = questionFileTextInputEditText.getText().toString();
            saveTopicToDatabase(title.isEmpty() ? fileName : title, extractedContent);
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

            Log.i(TAG, "Topic saved with title: " + title);
            Toast.makeText(this, "Content saved successfully", Toast.LENGTH_SHORT).show();
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

        // Get file name
        documentFile = DocumentFile.fromSingleUri(this, selectedFileUri);
        fileName = documentFile != null ? documentFile.getName() : "Unknown File";
    }

    private void setTexts() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setView(R.layout.progress_dialog);
        builder.setCancelable(false);
        builder.setNegativeButton("Dismiss", (dialog, which) -> {
            dialog.dismiss();
            executor.shutdownNow();
        });
        AlertDialog dialog = builder.create();
        handler.post(dialog::show);

        executor.execute(() -> {
            try {
                contentResolver = getContentResolver();
                inputStream = contentResolver.openInputStream(selectedFileUri);

                if ("PDF".equalsIgnoreCase(fileExtension)) {
                    extractPdfContent();
                } else if ("XLS".equalsIgnoreCase(fileExtension) || "XLSX".equalsIgnoreCase(fileExtension)) {
                    extractExcelContent();
                } else if ("CSV".equalsIgnoreCase(fileExtension)) {
                    extractCSVContent();
                } else if ("TXT".equalsIgnoreCase(fileExtension)) {
                    extractTextContent();
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    });
                    return;
                }

                runOnUiThread(() -> {
                    // Update UI elements
                    fileNameTV.setText("Filename: " + fileName);
                    fileTypeTV.setText("File Type: " + fileExtension);
                    questionFileTextInputEditText.setText(fileName);
                    dialog.dismiss();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Log.e (TAG, "Error extracting content: " + e.getMessage());
                    Toast.makeText(this, "Error extracting content", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
            }
        });
    }

    private void extractPdfContent() {
        try {
            reader = new PdfReader(inputStream);
            StringBuilder pdfContent = new StringBuilder();
            for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                pdfContent.append(PdfTextExtractor.getTextFromPage(reader, i));
            }
            extractedContent = pdfContent.toString();
            reader.close();
        } catch (IOException e) {
            Log.e(TAG, "Error reading PDF: " + e.getMessage());
        }
    }

    private void extractExcelContent() {
        try {
            Workbook workbook;
            if (fileExtension.equalsIgnoreCase("XLSX")) {
                workbook = new XSSFWorkbook(inputStream);
            } else {
                workbook = new HSSFWorkbook(inputStream);
            }
            StringBuilder excelContent = new StringBuilder();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        switch (cell.getCellType()) {
                            case STRING:
                                excelContent.append(cell.getStringCellValue()).append(" ");
                                break;
                            case NUMERIC:
                                excelContent.append(cell.getNumericCellValue()).append(" ");
                                break;
                            case BOOLEAN:
                                excelContent.append(cell.getBooleanCellValue()).append(" ");
                                break;
                            default:
                                break;
                        }
                    }
                    excelContent.append("\n");
                }
            }
            extractedContent = excelContent.toString();
            workbook.close();
        } catch (IOException e) {
            Log.e(TAG, "Error reading Excel: " + e.getMessage());
        }
    }

    private void extractCSVContent() {
        try (CSVReader csvReader = new CSVReader(new InputStreamReader(inputStream))) {
            StringBuilder csvContent = new StringBuilder();
            String[] nextLine;
            while ((nextLine = csvReader.readNext()) != null) {
                for (String cell : nextLine) {
                    csvContent.append(cell).append(" ");
                }
                csvContent.append("\n");
            }
            extractedContent = csvContent.toString();
        } catch (IOException | CsvValidationException e) {
            Log.e(TAG, "Error reading CSV: " + e.getMessage());
        }
    }

    private void extractTextContent() {
        try (InputStreamReader reader = new InputStreamReader(inputStream)) {
            StringBuilder textContent = new StringBuilder();
            int data;
            while ((data = reader.read()) != -1) {
                textContent.append((char) data);
            }
            extractedContent = textContent.toString();
        } catch (IOException e) {
            Log.e(TAG, "Error reading text file: " + e.getMessage());
        }
    }
}
