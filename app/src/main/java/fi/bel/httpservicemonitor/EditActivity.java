package fi.bel.httpservicemonitor;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

public class EditActivity extends Activity implements View.OnClickListener {
    protected static final String TAG = EditActivity.class.getSimpleName();
    protected static final long CHECK_INTERVAL_MS = 1000 * 60 * 5;
    protected static final int ALERT_NOTIFICATION = 1;

    protected SQLiteDatabase state;

    protected long id;

    protected EditText nameField;

    protected EditText addressField;

    protected Button saveButton;

    protected Button deleteButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        state = MainActivity.openDatabase(this);

        setContentView(R.layout.activity_edit);
        nameField = (EditText) findViewById(R.id.name);
        addressField = (EditText) findViewById(R.id.address);
        saveButton = (Button) findViewById(R.id.save);
        deleteButton = (Button) findViewById(R.id.delete);

        id = getIntent().getLongExtra("id", 0);
        if (id != 0) {
            Cursor cursor = state.rawQuery("select name, address from url where _id = ?",
                    new String[] { String.valueOf(id) });
            if (cursor.moveToNext()) {
                nameField.setText(cursor.getString(0));
                addressField.setText(cursor.getString(1));
            }
            cursor.close();
        } else {
            deleteButton.setVisibility(View.GONE);
        }

        saveButton.setOnClickListener(this);
        deleteButton.setOnClickListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MainActivity.closeDatabase();
    }

    @Override
    public void onClick(View view) {
        if (view == deleteButton) {
            state.execSQL("delete from url where _id = ?", new Object[] { id });
        }

        if (view == saveButton) {
            if (nameField.getText().length() == 0) {
                nameField.setError(getString(R.string.required));
                return;
            }
            if (addressField.getText().length() == 0) {
                addressField.setError(getString(R.string.required));
                return;
            }

            if (id == 0) {
                state.execSQL("insert into url (name, address, lastOk, lastCheck, status, notified) values (?, ?, 0, 0, 'NEW', 0)",
                        new Object[]{nameField.getText(), addressField.getText()});
            } else {
                state.execSQL("update url set name = ?, address = ?, lastOk = 0, lastCheck = 0, status = 'EDIT', notified = 0 where _id = ?",
                        new Object[]{nameField.getText(), addressField.getText(), id});
            }
        }

        sendBroadcast(new Intent("fi.bel.httpservicemonitor.Refresh"));
        finish();
    }
}