package fi.bel.httpservicemonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * This class handles the repeating poll alarm.
 *
 * Created by alankila on 10.12.14.
 */
public class ServiceUpdate extends BroadcastReceiver {
    protected static final String TAG = ServiceUpdate.class.getSimpleName();
    protected static final int NETWORK_TIMEOUT_MS = 30000;

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
                pendingResult = goAsync();
            }
            tasks ++;
        }

        @Override
        protected Object doInBackground(Void... o) {
            try {
                int result = -1;
                for (int i = 0; i < 3; i ++) {
                    Log.i(TAG, "Poll " + address + ", attempt: " + (i + 1));
                    result = doInBackgroundWithExceptions();
                    Log.i(TAG, "Poll " + address + ": " + result);
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
            SQLiteDatabase base = MainActivity.openDatabase(context);
            long now = System.currentTimeMillis();
            if (Integer.valueOf(200).equals(result)) {
                Log.i(TAG, "Handling poll " + address + " ok");
                base.execSQL("update url set lastCheck = ?, lastOk = ?, status = ?, notified = ? where _id = ?",
                        new Object[] { now, now, "OK", 0, id });
            } else {
                Log.w(TAG, "Handling poll " + address + " error: " + result);
                base.execSQL("update url set lastCheck = ?, status = ? where _id = ?",
                        new Object[] { now, "FAIL", id });
            }
            MainActivity.closeDatabase();

            /* Refresh the view each time we get results. */
            context.sendBroadcast(new Intent("fi.bel.httpservicemonitor.Refresh"));

            tasks --;
            if (tasks == 0) {
                pendingResult.finish();
                pendingResult = null;

                /* After all updates have completed, perform service fault check. */
                MainActivity.serviceFaultCheck(context);
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Update alarm fired");

        /* If we are already working, then don't add any new checks. */
        if (tasks != 0) {
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
            CheckServiceTask task = new CheckServiceTask(context, id, address);
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
        MainActivity.closeDatabase();
    }
}
