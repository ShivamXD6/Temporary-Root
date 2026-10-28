package build.bytes.temproot;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;

public class AboutActivity extends AppCompatActivity {
    private static final String GITHUB_APP = "https://github.com/ShivamXD6/Temporary-Root";
    private static final String TELEGRAM_CHANNEL = "https://t.me/BuildBytes";
    /** Empty hides the row. */
    private static final String TELEGRAM_GROUP = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        View root = findViewById(R.id.aboutRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.aboutToolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.aboutVersion)).setText(
                "Version " + BuildConfig.VERSION_NAME + " · kernel-assisted temporary root");

        LinearLayout links = findViewById(R.id.linkContainer);
        LayoutInflater inflater = LayoutInflater.from(this);
        addLink(inflater, links, "Telegram channel", "Builds and release notes", TELEGRAM_CHANNEL);
        addLink(inflater, links, "Telegram group", "Support and discussion", TELEGRAM_GROUP);
        addLink(inflater, links, "Source code", "github.com/ShivamXD6/Temporary-Root", GITHUB_APP);
    }

    private void addLink(LayoutInflater inflater, LinearLayout container,
                         String title, String subtitle, String url) {
        if (url == null || url.isEmpty()) return;
        View row = inflater.inflate(R.layout.item_link, container, false);
        ((TextView) row.findViewById(R.id.linkTitle)).setText(title);
        ((TextView) row.findViewById(R.id.linkSubtitle)).setText(subtitle);
        row.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show();
            }
        });
        container.addView(row);
    }
}
