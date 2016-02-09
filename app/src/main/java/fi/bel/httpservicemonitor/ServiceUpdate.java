package fi.bel.httpservicemonitor;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.util.Log;
import android.widget.RemoteViews;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.MessageFormat;

/**
 * This class handles the repeating poll alarm.
 *
 * Created by alankila on 10.12.14.
 */
public class ServiceUpdate extends BroadcastReceiver {
    protected static final String TAG = ServiceUpdate.class.getSimpleName();
    protected static final int NETWORK_TIMEOUT_MS = 15000;
    protected static final int ALERT_NOTIFICATION_ID = 1;

    protected int tasks;
    protected PendingResult pendingResult;

    protected class CheckServiceTask extends AsyncTask<Void, Void, Object> {
        protected final Context context;
        protected final long id;
        protected final String address;

        protected CheckServiceTask(Context context, long id, String address) {
            this.context = context;
            this.id = id;
            this.address = address;
        }

        @Override
        protected void onPreExecute() {
            if (tasks == 0) {
                Log.i(TAG, "New work: entering async mode");
                pendingResult = goAsync();
            }
            tasks ++;
        }

        @Override
        protected Object doInBackground(Void... o) {
            Object result = null;
            for (int i = 1; i <= 5; i ++) {
                Log.i(TAG, "Poll " + address + " attempt " + i);
                try {
                    result = doInBackgroundWithExceptions();
                } catch (IOException ioe) {
                    result = ioe;
                }

                Log.i(TAG, "Poll " + address + " result: " + result);
                if (result.equals(200)) {
                    break;
                }

                try {
                    Thread.sleep(5000);
                }
                catch (InterruptedException ie) {
                    break;
                }
            }
            return result;
        }

        protected int doInBackgroundWithExceptions() throws IOException {
            URL url = new URL(address);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(NETWORK_TIMEOUT_MS);
            connection.setReadTimeout(NETWORK_TIMEOUT_MS);
            connection.setDoOutput(false);
            connection.setDoInput(false);
            int code = connection.getResponseCode();
            connection.disconnect();
            return code;
        }

        @Override
        protected void onPostExecute(Object result) {
            Log.i(TAG, "Updating database with " + address + ": " + result);
            SQLiteDatabase base = MainActivity.openDatabase(context);
            long now = System.currentTimeMillis();
            if (result.equals(200)) {
                base.execSQL("update url set lastCheck = ?, lastOk = ?, status = ?, notified = ? where _id = ?",
                        new Object[] { now, now, "OK", 0, id });
            } else {
                base.execSQL("update url set lastCheck = ?, status = ? where _id = ?",
                        new Object[] { now, "FAIL", id });
            }
            MainActivity.closeDatabase();

            /* Refresh the view each time we get results. */
            context.sendBroadcast(new Intent("fi.bel.httpservicemonitor.Refresh"));

            tasks --;
            if (tasks == 0) {
                /* After all updates have completed, perform service fault check. */
                serviceFaultCheck();

                Log.i(TAG, "No more work: exiting async mode");
                pendingResult.finish();
                pendingResult = null;
            }
        }

        /**
         * Make ungodly racket if there are failures in any monitored service
         */
        protected void serviceFaultCheck() {
            long lastOkTooOld = System.currentTimeMillis() - MainActivity.REACT_INTERVAL_MS;

            /* First figure out how many are currently in alarm state */
            SQLiteDatabase base = MainActivity.openDatabase(context);
            Cursor cursor = base.rawQuery("select min(lastOk), count(*) from url where lastOk < ? and status = 'FAIL'",
                    new String[] { String.valueOf(lastOkTooOld) });
            cursor.moveToFirst();
            long lastOk = cursor.getLong(0);
            long count = cursor.getLong(1);
            cursor.close();

            /* Then figure out when which have been notified so far */
            cursor = base.rawQuery("select count(*) from url where lastOk < ? and status = 'FAIL' and notified = 0",
                    new String[] { String.valueOf(lastOkTooOld) });
            cursor.moveToFirst();
            long newCount = cursor.getLong(0);
            cursor.close();

            /* Update everyone to notified */
            if (newCount > 0) {
                base.execSQL("update url set notified = 1 where lastOk < ? and status = 'FAIL' and notified = 0",
                        new String[] { String.valueOf(lastOkTooOld) });
            }
            MainActivity.closeDatabase();

            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (count == 0) {
                Log.i(TAG, "No alarm required, everything is now OK");
                nm.cancel(ALERT_NOTIFICATION_ID);
            }
            if (newCount != 0) {
                Log.i(TAG, "Alarm required, new failures: " + newCount);
                /* Make a super obnoxious alert */
                Notification n = new Notification();
                //n.category = Notification.CATEGORY_ALARM;
                n.priority = Notification.PRIORITY_HIGH;
                n.when = lastOk;
                n.icon = R.drawable.ic_launcher;
                n.contentIntent = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), 0);
                n.tickerText = MessageFormat.format("Old problems: {0}; New problems: {1}", count, newCount);
                n.flags = Notification.FLAG_INSISTENT | Notification.FLAG_SHOW_LIGHTS | Notification.FLAG_AUTO_CANCEL | Notification.FLAG_ONGOING_EVENT;
                n.ledARGB = 0xff0000;
                n.ledOffMS = 400;
                n.ledOnMS = 100;
                n.sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                n.vibrate = new long[] { 1000, 1000};
                nm.notify(ALERT_NOTIFICATION_ID, n);
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Update request received");

        /* If we are already working, then don't add any new checks. */
        if (tasks != 0) {
            Log.w(TAG, "Not polling: prior tasks still running");
            return;
        }

        /* If we have no network, we can't poll. */
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = manager.getActiveNetworkInfo();
        if (activeNetwork == null || !activeNetwork.isConnected()) {
            Log.w(TAG, "Not polling: network is not connected");
            return;
        }

        /* Schedule all check tasks */
        SQLiteDatabase base = MainActivity.openDatabase(context);
        Cursor cursor = base.rawQuery(
                "select _id, address from url where lastCheck < ? or status = 'FAIL' order by _id",
                new String[] { String.valueOf(System.currentTimeMillis() - MainActivity.CHECK_INTERVAL_MS / 2) }
        );
        while (cursor.moveToNext()) {
            long id = cursor.getLong(0);
            String address = cursor.getString(1);
            Log.i(TAG, "Scheduling check of: " + address);
            CheckServiceTask task = new CheckServiceTask(context, id, address);
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
        cursor.close();
        MainActivity.closeDatabase();
    }
}
