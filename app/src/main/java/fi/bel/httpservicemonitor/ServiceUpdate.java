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
                return doInBackgroundWithExceptions();
            } catch (IOException ioe) {
                return ioe;
            }
        }

        protected Integer doInBackgroundWithExceptions() throws IOException {
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
                Log.i(TAG, "Handling service " + id + " ok");
                base.execSQL("update url set lastCheck = ?, lastOk = ?, status = ?, notified = ? where _id = ?",
                        new Object[] { now, now, "OK", 0, id });
            } else {
                Log.w(TAG, "Handling service " + id + " error: " + result);
                base.execSQL("update url set lastCheck = ?, status = ? where _id = ?",
                        new Object[] { now, "FAIL", id });
            }
            MainActivity.closeDatabase();

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
