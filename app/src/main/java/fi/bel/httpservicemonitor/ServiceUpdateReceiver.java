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
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * This class handles the repeating poll alarm.
 *
 * Created by alankila on 10.12.14.
 */
public class ServiceUpdateReceiver extends BroadcastReceiver {
    protected static final String TAG = ServiceUpdateReceiver.class.getSimpleName();
    protected static final int NETWORK_TIMEOUT_MS = 15000;
    protected static final int ALERT_NOTIFICATION_ID = 1;

    /**
     * AsyncTask that eventually resolves to either status code or Exception
     */
    protected static class CheckServiceTask extends AsyncTask<Void, Void, Object> {
        protected final String address;

        protected CheckServiceTask(String address) {
            this.address = address;
        }

        @Override
        protected Object doInBackground(Void... o) {
            Object result = null;
            for (int i = 1; i <= 5; i++) {
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
                } catch (InterruptedException ie) {
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
    }

    /**
     * AsyncTask that coordinates other asynctasks and runs its payload once all are ready.
     * The payload checks the results and maybe triggers alarm and UI updates.
     */
    protected static class CoordinateWork extends AsyncTask<Void, Void, Void> {
        protected final Context applicationContext;
        protected final Map<Long, String> addressMap;
        protected final Map<Long, AsyncTask<Void, Void, Object>> taskMap = new HashMap<>();
        protected PowerManager.WakeLock lock;

        protected CoordinateWork(Context applicationContext, Map<Long, String> addressMap) {
            this.applicationContext = applicationContext;
            this.addressMap = addressMap;
        }

        @Override
        protected void onPreExecute() {
            PowerManager pm = (PowerManager) applicationContext.getSystemService(Context.POWER_SERVICE);
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            lock.acquire();

            for (Map.Entry<Long, String> e : addressMap.entrySet()) {
                CheckServiceTask task = new CheckServiceTask(e.getValue());
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                taskMap.put(e.getKey(), task);
            }
        }

        @Override
        protected Void doInBackground(Void... voids) {
            for (Map.Entry<Long, AsyncTask<Void, Void, Object>> e : taskMap.entrySet()) {
                try {
                    Object result = e.getValue().get();
                    handleResult(e.getKey(), result);
                } catch (Exception ex) {
                    Log.e(TAG, "Exception while waiting one task", ex);
                }
            }
            return null;
        }

        protected void handleResult(Long id, Object result) {
            Log.i(TAG, "Updating database with " + id + ": " + result);
            long now = System.currentTimeMillis();
            try (SQLiteDatabase base = MainActivity.openDatabase(applicationContext)) {
                /* trust 200 OK no matter what */
                if (result.equals(200)) {
                    base.execSQL("update url set lastCheck = ?, lastOk = ?, status = ? where _id = ?",
                            new Object[]{now, now, "OK", id});
                } else if (isNetworkConnected(applicationContext)) {
                    base.execSQL("update url set lastCheck = ?, status = ? where _id = ?",
                            new Object[]{now, "FAIL", id});
                } else {
                    Log.w(TAG, "Network is no longer connected, ignoring failure");
                }
            }
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            /* In case MainActivity is listening, tell it about new data. */
            applicationContext.sendBroadcast(new Intent("fi.bel.httpservicemonitor.Refresh"));

            /* Refresh the view each time we get results. */
            serviceFaultCheck();

            lock.release();
        }

        /**
         * Make ungodly racket if there are failures in any monitored service
         */
        protected void serviceFaultCheck() {
            long lastOkTooOld = System.currentTimeMillis() - MainActivity.REACT_INTERVAL_MS;

            /* First figure out how many are currently in alarm state */
            long lastOk;
            int failureCount;
            int oldFailureCount;
            try (SQLiteDatabase base = MainActivity.openDatabase(applicationContext)) {
                try (Cursor cursor = base.rawQuery("select min(lastOk), count(*) from url where status = 'FAIL'",
                        new String[] {})) {
                    cursor.moveToFirst();
                    lastOk = cursor.getLong(0);
                    failureCount = cursor.getInt(1);
                }

                /* Then figure out when which are old */
                try (Cursor cursor = base.rawQuery("select count(*) from url where lastOk < ? and status = 'FAIL'",
                        new String[] {String.valueOf(lastOkTooOld)})) {
                    cursor.moveToFirst();
                    oldFailureCount = cursor.getInt(0);
                }
            }

            NotificationManager nm = (NotificationManager) applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);

            Notification.Builder nb = new Notification.Builder(applicationContext);
            nb.setPriority(Notification.PRIORITY_HIGH);
            nb.setWhen(lastOk);
            nb.setSmallIcon(R.drawable.ic_launcher);
            nb.setContentTitle("Some services not reachable");
            nb.setContentText(MessageFormat.format("Problems detected", failureCount));
            nb.setContentIntent(PendingIntent.getActivity(applicationContext, 0, new Intent(applicationContext, MainActivity.class), 0));
            nb.setAutoCancel(true);

            if (failureCount == 0) {
                Log.i(TAG, "No alarm required, everything is now OK");
                nm.cancel(ALERT_NOTIFICATION_ID);
            } else if (oldFailureCount == 0) {
                Log.i(TAG, "Weak alarm required, ongoing failures: " + failureCount);
                nb.setNumber(failureCount);
                nb.setLights(0xbbbb00, 100, 400);
                Notification n = nb.build();
                nm.notify(ALERT_NOTIFICATION_ID, n);
            } else {
                Log.i(TAG, "Strong alarm required, old failures: " + oldFailureCount);
                /* Make a super obnoxious alert */
                nb.setNumber(oldFailureCount);
                nb.setLights(0xff0000, 100, 400);
                nb.setVibrate(new long[] { 1000, 1000 });
                nb.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
                Notification n = nb.build();
                n.flags |= Notification.FLAG_INSISTENT;
                nm.notify(ALERT_NOTIFICATION_ID, n);
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Update request received");
        if (!isNetworkConnected(context)) {
            Log.i(TAG, "Not running, network is not connected.");
            return;
        }

        Map<Long, String> addressMap = new HashMap<>();
        try (SQLiteDatabase base = MainActivity.openDatabase(context);
            Cursor cursor = base.rawQuery(
                    "select _id, address from url where lastCheck < ? or status = 'FAIL' order by _id",
                    new String[] {String.valueOf(System.currentTimeMillis() - MainActivity.CHECK_INTERVAL_MS / 2)})) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String address = cursor.getString(1);
                addressMap.put(id, address);
            }
        }

        if (!addressMap.isEmpty()) {
            new CoordinateWork(context.getApplicationContext(), addressMap)
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    protected static boolean isNetworkConnected(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = manager.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }
}
