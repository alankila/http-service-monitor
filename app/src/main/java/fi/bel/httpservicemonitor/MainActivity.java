package fi.bel.httpservicemonitor;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;

import java.text.MessageFormat;
import java.util.Date;
import java.util.HashMap;

public class MainActivity extends Activity implements ListView.OnItemClickListener, View.OnClickListener {
    protected static final String TAG = MainActivity.class.getSimpleName();
    protected static final long CHECK_INTERVAL_MS = 1000 * 60 * 10;
    protected static final int ALERT_NOTIFICATION_ID = 1;

    protected static int stateCount;
    protected static SQLiteDatabase state;

    protected ListView listView;
    protected SimpleCursorAdapter listViewAdapter;

    protected CheckBox activeBox;

    /**
     * Ensure that we have a running handler for our alarms.
     *
     * @param context
     */
    protected static void initializeAlarm(Context context) {
        PendingIntent checkIntent = PendingIntent.getBroadcast(context, 0, new Intent(context, ServiceUpdate.class), 0);

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        boolean prefs = preferences(context).getBoolean("active", false);
        if (prefs) {
            Log.i(TAG, "Telling AlarmManager to run ourselves every " + CHECK_INTERVAL_MS + " ms");
            alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, 0, CHECK_INTERVAL_MS, checkIntent);
        } else {
            Log.i(TAG, "Telling AlarmManager to cease invoking us.");
            alarmManager.cancel(checkIntent);
        }
    }

    /**
     * Return initialized database handle suitable for querying.
     *
     * @param context
     * @return open database
     */
    protected static SQLiteDatabase openDatabase(Context context) {
        if (stateCount == 0) {
            state = context.openOrCreateDatabase("state", Context.MODE_PRIVATE, null);
            state.execSQL("create table if not exists url (_id integer primary key, name text, address text, lastOk text, lastCheck text, status text, notified int)");
        }
        stateCount ++;
        return state;
    }

    protected static void closeDatabase() {
        stateCount --;
        if (stateCount == 0) {
            state.close();
            state = null;
        }
    }

    /**
     * Make ungodly racket if there are failures in any monitored service
     *
     * @param context
     */
    protected static void serviceFaultCheck(Context context) {
        SQLiteDatabase base = openDatabase(context);
        Cursor cursor = base.rawQuery("select min(lastOk), max(lastCheck), count(*) from url where status = 'FAIL'", new String[] {});
        cursor.moveToFirst();
        long lastOk = cursor.getLong(0);
        long lastCheck = cursor.getLong(1);
        long count = cursor.getLong(2);
        cursor.close();

        cursor = base.rawQuery("select count(*) from url where status = 'FAIL' and notified = 0", new String[] {});
        cursor.moveToFirst();
        long newCount = cursor.getLong(0);
        cursor.close();

        /* Update everyone to notified */
        if (newCount > 0) {
            base.execSQL("update url set notified = 1 where status = 'FAIL' and notified = 0");
        }
        closeDatabase();

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (count == 0) {
            Log.i(TAG, "No alarm required, everything is OK");
            nm.cancel(ALERT_NOTIFICATION_ID);
        }
        if (newCount != 0) {
            Log.i(TAG, "Alarm required, new failures: " + newCount);
            /* Make a super obnoxious alert */
            Notification.Builder troubleNotification = new Notification.Builder(context);
            troubleNotification.setCategory(Notification.CATEGORY_ALARM);
            troubleNotification.setPriority(Notification.PRIORITY_HIGH);
            troubleNotification.setWhen(lastOk);

            troubleNotification.setSmallIcon(R.drawable.ic_launcher);
            troubleNotification.setContentTitle(MessageFormat.format("Problems at {0} services", count));
            troubleNotification.setContentIntent(PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), 0));
            troubleNotification.setContentText(MessageFormat.format("New problems detected: {0}", newCount));
            troubleNotification.setLights(0xff0000, 100, 400);
            troubleNotification.setVibrate(new long[] { 1000, 1000 });
            troubleNotification.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            troubleNotification.setOnlyAlertOnce(false);

            nm.notify(ALERT_NOTIFICATION_ID, troubleNotification.build());
        }
    }

    protected static SharedPreferences preferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE);
        if (!prefs.contains("active")) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("active", true);
            editor.commit();
        }
        return prefs;
    }

    protected BroadcastReceiver refresh = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Main view refresh requested");
            listViewAdapter.changeCursor(buildCursor());

            Cursor cursor = state.rawQuery("select min(lastCheck) from url", new String[] {});
            cursor.moveToFirst();
            Long time = cursor.getLong(0);
            cursor.close();
            String text = context.getString(R.string.active) + " ";
            if (time != null) {
                text += MessageFormat.format("{1,date,yyyy-MM-dd HH:mm:ss}",
                        context.getString(R.string.active),
                        new Date(time)
                );
            } else {
                text += "-";
            }
            activeBox.setText(text);
        }
    };

    protected Cursor buildCursor() {
        return state.rawQuery(
                "select _id, name, address, status, lastOk, lastCheck from url order by _id",
                new String[] {}
        );
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        state = openDatabase(this);

        initializeAlarm(this);

        setContentView(R.layout.activity_main);

        listView = (ListView) findViewById(R.id.listView);
        listViewAdapter = new SimpleCursorAdapter(
                this,
                android.R.layout.simple_list_item_2,
                buildCursor(),
                new String[] { "name", "status" }, new int[] { android.R.id.text1, android.R.id.text2 },
                0
        );
        listView.setOnItemClickListener(this);
        listView.setAdapter(listViewAdapter);

        activeBox = (CheckBox) findViewById(R.id.active);
        activeBox.setChecked(preferences(this).getBoolean("active", false));
        activeBox.setOnClickListener(this);

        registerReceiver(refresh, new IntentFilter("fi.bel.httpservicemonitor.Refresh"));
        sendBroadcast(new Intent("fi.bel.httpservicemonitor.Refresh"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(refresh);
        closeDatabase();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(TAG, "Handling menu press of item: " + item.getTitle());
        int id = item.getItemId();
        if (id == R.id.action_add) {
            Intent editIntent = new Intent(this, EditActivity.class);
            startActivity(editIntent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** For listview item click -> edit */
    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int pos, long l) {
        Log.i(TAG, "Handling activation of list view item at position: " + pos);
        Cursor cursor = (Cursor) adapterView.getItemAtPosition(pos);
        Intent editIntent = new Intent(this, EditActivity.class);
        editIntent.putExtra("id", cursor.getLong(0));
        startActivity(editIntent);
    }

    /** For active checkbox click -> enable/disable */
    @Override
    public void onClick(View view) {
        Log.i(TAG, "Handling click on: " + view);
        if (view == activeBox) {
            SharedPreferences.Editor editor = preferences(this).edit();
            editor.putBoolean("active", activeBox.isChecked());
            editor.commit();
            initializeAlarm(this);
        }
    }
}
