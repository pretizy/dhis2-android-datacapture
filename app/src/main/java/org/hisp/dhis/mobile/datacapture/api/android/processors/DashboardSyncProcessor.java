package org.hisp.dhis.mobile.datacapture.api.android.processors;

import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.util.Log;

import org.hisp.dhis.mobile.datacapture.BusProvider;
import org.hisp.dhis.mobile.datacapture.api.APIException;
import org.hisp.dhis.mobile.datacapture.api.android.events.OnDashboardsSyncedEvent;
import org.hisp.dhis.mobile.datacapture.api.android.handlers.DashboardHandler;
import org.hisp.dhis.mobile.datacapture.api.android.handlers.DashboardItemHandler;
import org.hisp.dhis.mobile.datacapture.api.android.models.DBItemHolder;
import org.hisp.dhis.mobile.datacapture.api.android.models.ResponseHolder;
import org.hisp.dhis.mobile.datacapture.api.android.models.State;
import org.hisp.dhis.mobile.datacapture.api.managers.DHISManager;
import org.hisp.dhis.mobile.datacapture.api.models.Dashboard;
import org.hisp.dhis.mobile.datacapture.api.models.DashboardItem;
import org.hisp.dhis.mobile.datacapture.api.network.ApiRequestCallback;
import org.hisp.dhis.mobile.datacapture.api.network.Response;
import org.hisp.dhis.mobile.datacapture.io.DBContract;
import org.hisp.dhis.mobile.datacapture.io.DBContract.DashboardColumns;
import org.hisp.dhis.mobile.datacapture.io.DBContract.DashboardItemColumns;
import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hisp.dhis.mobile.datacapture.utils.DateTimeTypeAdapter.deserializeDateTime;

public class DashboardSyncProcessor extends AsyncTask<Void, Void, OnDashboardsSyncedEvent> {
    private static final String TAG = DashboardSyncProcessor.class.getSimpleName();
    private Context mContext;

    public DashboardSyncProcessor(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }

        mContext = context;
    }

    private static DashboardItem getDashboardItem(String id) throws APIException {
        final ResponseHolder<DashboardItem> holder = new ResponseHolder<>();

        DHISManager.getInstance().getDashboardItem(new ApiRequestCallback<DashboardItem>() {
            @Override
            public void onSuccess(Response response, DashboardItem item) {
                holder.setResponse(response);
                holder.setItem(item);
            }

            @Override
            public void onFailure(APIException e) {
                holder.setException(e);
            }
        }, id);

        if (holder.getException() != null) {
            throw holder.getException();
        } else {
            return holder.getItem();
        }
    }

    private static List<Dashboard> getDashboards() throws APIException {
        final ResponseHolder<List<Dashboard>> holder = new ResponseHolder<>();

        DHISManager.getInstance().getDashboards(new ApiRequestCallback<List<Dashboard>>() {
            @Override
            public void onSuccess(Response response, List<Dashboard> dashboards) {
                holder.setResponse(response);
                holder.setItem(dashboards);
            }

            @Override
            public void onFailure(APIException e) {
                holder.setException(e);
            }
        });

        if (holder.getException() != null) {
            throw holder.getException();
        } else {
            return holder.getItem();
        }
    }

    private ArrayList<ContentProviderOperation> updateDashboards() throws APIException {
        final String SELECTION = DashboardItemColumns.STATE + " = " +
                "'" + State.GETTING.toString() + "'";
        List<DBItemHolder<Dashboard>> oldDashboards = readDashboards(SELECTION);
        List<Dashboard> newDashboardList = getDashboards();
        Map<String, Dashboard> newDashboards = DashboardHandler.toMap(newDashboardList);
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        for (DBItemHolder<Dashboard> oldDashboard : oldDashboards) {
            Dashboard newDashboard = newDashboards.get(oldDashboard.getItem().getId());

            // if dashboard which we have recently got from server is empty,
            // it means it has been removed
            if (newDashboard == null) {
                Log.d(TAG, "Removing dashboard {id, name}: " +
                        oldDashboard.getItem().getId() + " : " + oldDashboard.getItem().getName());
                deleteDashboard(ops, oldDashboard);
                continue;
            }

            DateTime newLastUpdated = deserializeDateTime(newDashboard.getLastUpdated());
            DateTime oldLastUpdated = deserializeDateTime(oldDashboard.getItem().getLastUpdated());

            if (newLastUpdated.isAfter(oldLastUpdated)) {
                Log.d(TAG, "Updating dashboard {id, name}: " +
                        oldDashboard.getItem().getId() + " : " + oldDashboard.getItem().getName());
                updateDashboard(ops, oldDashboard, newDashboard);
            }

            newDashboards.remove(oldDashboard.getItem().getId());
        }

        // Inserting new items here
        for (String key : newDashboards.keySet()) {
            Dashboard dashboard = newDashboards.get(key);
            Log.d(TAG, "Inserting new dashboard {id, name}: " + dashboard.getName() + " " + dashboard.getId());
            insertDashboard(ops, dashboard);
        }

        return ops;
    }

    private void insertDashboard(List<ContentProviderOperation> ops,
                                 Dashboard dashboard) throws APIException {
        final List<DashboardItem> dashboardItems = new ArrayList<>();

        ContentProviderOperation dashboardInsertOp = DashboardHandler.insert(dashboard);
        if (dashboardInsertOp == null) {
            return;
        }

        ops.add(dashboardInsertOp);
        if (dashboard.getDashboardItems() == null || dashboard.getDashboardItems().size() <= 0) {
            return;
        }

        // we have to download full sized dashboard items before inserting them
        for (DashboardItem shortDashItem : dashboard.getDashboardItems()) {
            dashboardItems.add(getDashboardItem(shortDashItem.getId()));
        }

        if (dashboardItems.size() <= 0) {
            return;
        }

        final int dashboardIndex = ops.size() - 1;
        for (DashboardItem dashboardItem : dashboardItems) {
            Log.i(TAG, "Inserting dashboard item {id}: " + dashboardItem.getId());
            ContentProviderOperation op = DashboardItemHandler.insertWithBackReference(dashboardIndex, dashboardItem);
            if (op != null) {
                ops.add(op);
            }
        }
    }

    private void updateDashboard(List<ContentProviderOperation> ops,
                                 DBItemHolder<Dashboard> oldDashboard,
                                 Dashboard newDashboard) throws APIException {
        ContentProviderOperation dashboardUpdateOp = DashboardHandler.update(oldDashboard, newDashboard);

        if (dashboardUpdateOp == null) {
            return;
        }

        ops.add(dashboardUpdateOp);

        final String SELECTION = DashboardItemColumns.DASHBOARD_DB_ID + " = " + oldDashboard.getDatabaseId() + " AND " +
                DashboardItemColumns.STATE + " = " + "'" + State.GETTING.toString() + "'";
        List<DBItemHolder<DashboardItem>> oldDashboardItems = readDashboardItems(SELECTION);
        Map<String, DashboardItem> newDashboardItems = DashboardItemHandler.toMap(newDashboard.getDashboardItems());

        for (DBItemHolder<DashboardItem> oldDashboardItem : oldDashboardItems) {
            DashboardItem newDashboardItem = newDashboardItems.get(oldDashboardItem.getItem().getId());

            if (newDashboardItem == null) {
                Log.i(TAG, "Removing dashboard item {id}: " + oldDashboardItem.getItem().getId());
                ops.add(DashboardItemHandler.delete(oldDashboardItem));
                continue;
            }

            DateTime oldItemLastUpdated = deserializeDateTime(oldDashboardItem.getItem().getLastUpdated());
            DateTime newItemLastUpdated = deserializeDateTime(newDashboardItem.getLastUpdated());

            if (newItemLastUpdated.isAfter(oldItemLastUpdated)) {
                // newDashboardItem is actually a short version of DashboardItem from server,
                // We need to download a larger version from API, and then persist it
                DashboardItem newFullItem = getDashboardItem(oldDashboardItem.getItem().getId());
                ContentProviderOperation op = DashboardItemHandler.update(oldDashboardItem, newFullItem);
                if (op != null) {
                    Log.d(TAG, "Updating dashboard item {id}: " + oldDashboard.getItem().getId());
                    ops.add(op);
                }
            }

            newDashboardItems.remove(oldDashboardItem.getItem().getId());
        }

        for (String itemId : newDashboardItems.keySet()) {

            // we need to download full sized dashboard item before
            // updating old instance
            DashboardItem shortItem = newDashboardItems.get(itemId);
            DashboardItem fullItem = getDashboardItem(shortItem.getId());

            ContentProviderOperation op = DashboardItemHandler.insert(oldDashboard, fullItem);
            if (op != null) {
                Log.i(TAG, "Inserting dashboard item {id}: " + shortItem.getId());
                ops.add(op);
            }
        }
    }

    private void deleteDashboard(List<ContentProviderOperation> ops,
                                 DBItemHolder<Dashboard> dashboard) {
        ops.add(DashboardHandler.delete(dashboard));
    }

    private List<DBItemHolder<Dashboard>> readDashboards(String selection) {
        Cursor cursor = mContext.getContentResolver().query(
                DashboardColumns.CONTENT_URI, DashboardHandler.PROJECTION, selection, null, null
        );

        List<DBItemHolder<Dashboard>> dashboards = new ArrayList<>();
        if (cursor != null && cursor.getCount() > 0) {
            cursor.moveToFirst();

            do {
                dashboards.add(DashboardHandler.fromCursor(cursor));
            } while (cursor.moveToNext());
            cursor.close();
        }

        return dashboards;
    }

    private List<DBItemHolder<DashboardItem>> readDashboardItems(String selection) {
        Cursor cursor = mContext.getContentResolver().query(
                DashboardItemColumns.CONTENT_URI, DashboardItemHandler.PROJECTION, selection, null, null
        );

        List<DBItemHolder<DashboardItem>> items = new ArrayList<>();
        if (cursor != null && cursor.getCount() > 0) {
            cursor.moveToFirst();

            do {
                items.add(DashboardItemHandler.fromCursor(cursor));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return items;
    }

    @Override
    protected OnDashboardsSyncedEvent doInBackground(Void... params) {
        OnDashboardsSyncedEvent event = new OnDashboardsSyncedEvent();

        ResponseHolder<String> sendResponse = sendOfflineDashboards();
        if (sendResponse.getException() != null) {
            event.setSendResponse(sendResponse);
            return event;
        }

        ResponseHolder<String> retrieveResponse = refreshDashboards();
        if (retrieveResponse.getException() != null) {
            event.setRetrieveResponse(retrieveResponse);
            return event;
        }

        event.setSendResponse(sendResponse);
        event.setRetrieveResponse(retrieveResponse);
        return event;
    }

    private ResponseHolder<String> sendOfflineDashboards() {
        final ResponseHolder<String> holder = new ResponseHolder<>();

        ArrayList<ContentProviderOperation> ops = null;
        try {
            ops = postDashboards();
        } catch (APIException e) {
            holder.setException(e);
        }

        if (holder.getException() != null) {
            return holder;
        }

        try {
            mContext.getContentResolver().applyBatch(DBContract.AUTHORITY, ops);
        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (OperationApplicationException e) {
            e.printStackTrace();
        }

        return holder;
    }

    private ArrayList<ContentProviderOperation> postDashboards() throws APIException {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        final String SELECTION_DELETED = DashboardColumns.STATE + " = " +
                "'" + State.DELETING.toString() + "'";
        List<DBItemHolder<Dashboard>> removedDashboards = readDashboards(SELECTION_DELETED);
        for (DBItemHolder<Dashboard> dbItem : removedDashboards) {
            ResponseHolder<String> response = deleteDashboard(dbItem);
            if (response.getException() != null) {
                throw response.getException();
            } else {
                deleteDashboard(ops, dbItem);
            }
        }

        final String SELECTION_EDITED = DashboardColumns.STATE + " = " +
                "'" + State.PUTTING.toString() + "'";
        List<DBItemHolder<Dashboard>> editedDashboards = readDashboards(SELECTION_EDITED);
        for (DBItemHolder<Dashboard> dbItem : editedDashboards) {
            ResponseHolder<String> response = updateDashboard(dbItem);
            if (response.getException() != null) {
                throw response.getException();
            } else {
                ops.add(DashboardHandler.update(dbItem, dbItem.getItem()));
            }
        }

        List<DBItemHolder<Dashboard>> allDashboards = readDashboards(null);
        for (DBItemHolder<Dashboard> dashboardDbItem : allDashboards) {
            final String SELECTION_DELETED_ITEMS = DashboardItemColumns.DASHBOARD_DB_ID + " = " +
                    dashboardDbItem.getDatabaseId() + " AND " +
                    DashboardItemColumns.STATE + " = " + "'" + State.DELETING.toString() + "'";
            List<DBItemHolder<DashboardItem>> items = readDashboardItems(SELECTION_DELETED_ITEMS);
            for (DBItemHolder<DashboardItem> dbItem : items) {
                ResponseHolder<String> response = deleteDashboardItem(dashboardDbItem, dbItem);
                if (response.getException() != null) {
                    throw response.getException();
                } else {
                    ops.add(DashboardItemHandler.delete(dbItem));
                }
            }
        }

        return ops;
    }

    private ResponseHolder<String> deleteDashboardItem(DBItemHolder<Dashboard> dashboard,
                                                       DBItemHolder<DashboardItem> item) {
        final ResponseHolder<String> holder = new ResponseHolder<>();

        DHISManager.getInstance().deleteItemFromDashboard(new ApiRequestCallback<String>() {
            @Override
            public void onSuccess(Response response, String s) {
                holder.setResponse(response);
                holder.setItem(s);
            }

            @Override
            public void onFailure(APIException e) {
                holder.setException(e);
            }
        }, dashboard.getItem().getId(), item.getItem().getId());

        return holder;
    }

    private ResponseHolder<String> deleteDashboard(DBItemHolder<Dashboard> dbItem) {
        final ResponseHolder<String> holder = new ResponseHolder<>();

        DHISManager.getInstance().deleteDashboard(new ApiRequestCallback<String>() {
            @Override
            public void onSuccess(Response response, String s) {
                holder.setItem(s);
                holder.setResponse(response);
            }

            @Override
            public void onFailure(APIException e) {
                holder.setException(e);
            }
        }, dbItem.getItem().getId());

        return holder;
    }

    private ResponseHolder<String> updateDashboard(DBItemHolder<Dashboard> dbItem) {
        final ResponseHolder<String> holder = new ResponseHolder<>();
        final Dashboard dashboard = dbItem.getItem();

        DHISManager.getInstance().updateDashboardName(new ApiRequestCallback<String>() {
            @Override
            public void onSuccess(Response response, String s) {
                holder.setResponse(response);
                holder.setItem(s);
            }

            @Override
            public void onFailure(APIException e) {
                holder.setException(e);
            }
        }, dashboard.getId(), dashboard.getName());

        return holder;
    }

    private ResponseHolder<String> refreshDashboards() {
        final ResponseHolder<String> holder = new ResponseHolder<>();

        ArrayList<ContentProviderOperation> ops = null;
        try {
            ops = updateDashboards();
        } catch (APIException exception) {
            holder.setException(exception);
        }

        if (holder.getException() != null) {
            return holder;
        }

        try {
            mContext.getContentResolver().applyBatch(DBContract.AUTHORITY, ops);
        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (OperationApplicationException e) {
            e.printStackTrace();
        }

        return holder;
    }

    @Override
    protected void onPostExecute(OnDashboardsSyncedEvent event) {
        BusProvider.getInstance().post(event);
    }
}