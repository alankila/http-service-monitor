package fi.bel.httpservicemonitor;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.util.Log;

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
            try {
                int result = -1;
                for (int i = 1; i <= 5; i ++) {
                    Log.i(TAG, "Poll " + address + " attempt " + i);
                    result = doInBackgroundWithExceptions();
                    Log.i(TAG, "Poll " + address + " result: " + result);
                    if (result == 200) {
                        return result;
                    }
                }
                return result;
            } catch (IOException ioe) {
                return ioe;
            }
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
            if (Integer.valueOf(200).equals(result)) {
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

                nm.notify(ALERT_NOTIFICATION_ID, troubleNotification.build());

                /* Create 1 minute wakelock so that sound doesn't stop right away, for Android >= 5 */
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, ServiceUpdate.class.getSimpleName());
                wl.acquire(60000);
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
                "select _id, address from url where lastCheck < ? order by _id",
                new String[] { String.valueOf(System.currentTimeMillis() - MainActivity.CHECK_INTERVAL_MS) }
        );
        while (cursor.moveToNext()) {
            long id = cursor.getLong(0);
            String address = cursor.getString(1);
            Log.i(TAG, "Scheduling check of: " + address);
            CheckServiceTask task = new CheckServiceTask(context, id, address);
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
        MainActivity.closeDatabase();
    }
}
