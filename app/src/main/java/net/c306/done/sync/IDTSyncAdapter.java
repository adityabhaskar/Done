package net.c306.done.sync;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncRequest;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.text.Html;
import android.util.Log;

import com.google.gson.Gson;

import net.c306.done.DoneItem;
import net.c306.done.R;
import net.c306.done.TeamItem;
import net.c306.done.Utils;
import net.c306.done.db.DoneListContract;
import net.c306.done.idonethis.DeleteDonesTask;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;


public class IDTSyncAdapter extends AbstractThreadedSyncAdapter {
    
    private static final String LOG_TAG = Utils.LOG_TAG + IDTSyncAdapter.class.getSimpleName();
    
    public IDTSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }
    
    /**
     * Helper method to have the sync adapter sync immediately
     *
     * @param context The context used to access the account service
     */
    public static void syncImmediately(Context context, boolean fetchTeams) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        bundle.putBoolean(Utils.INTENT_EXTRA_FETCH_TEAMS, fetchTeams);
        
        Account syncAccount = IDTAccountManager.getSyncAccount(context);
        
        if (syncAccount != null)
            ContentResolver.requestSync(syncAccount, context.getString(R.string.content_authority), bundle);
        else
            Log.w(LOG_TAG, "No sync account found!");
    }
    
    public static void syncImmediately(Context context) {
        syncImmediately(context, false);
    }
    
    /**
     * Helper method to schedule the sync adapter periodic execution
     */
    public static void configurePeriodicSync(Context context, int syncInterval, int flexTime) {
        String authority = context.getString(R.string.content_authority);
        Account account = IDTAccountManager.getSyncAccount(context);
        
        if (account == null) {
            Log.e(LOG_TAG, "No account found to configure periodic sync");
            return;
        }
        
        SyncRequest request = new SyncRequest.Builder().
                syncPeriodic(syncInterval, flexTime).
                setSyncAdapter(account, authority).
                setExtras(new Bundle()).build();
        ContentResolver.requestSync(request);
    }
    
    public static void onAccountCreated(Account newAccount, Context context) {
        /*
         * Since we've created an account
         */
        IDTSyncAdapter.configurePeriodicSync(context, Utils.SYNC_INTERVAL, Utils.SYNC_FLEXTIME);
        
        /*
         * Without calling setSyncAutomatically, our periodic sync will not be enabled.
         */
        ContentResolver.setSyncAutomatically(newAccount, context.getString(R.string.content_authority), true);
        
        /*
         * Finally, let's do a sync to get things started
         */
        syncImmediately(context, true);
    }
    
    public static void initializeSyncAdapter(Context context, String username) {
        Log.v(LOG_TAG, "Initialising sync adapter");
        IDTAccountManager.createSyncAccount(context, username);
    }
    
    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
        Log.v(LOG_TAG, "onPerformSync Called.");
        
        String authToken = IDTAccountManager.getAuthToken(getContext());
        
        if (authToken == null)
            return;
        
        // 1. Refresh team list
        boolean fetchTeamsFlag = extras.getBoolean(Utils.INTENT_EXTRA_FETCH_TEAMS, true);
        int teamCount = 0;
        
        if (fetchTeamsFlag)
            teamCount = fetchTeams(authToken);
        
        // 2. Check for unsent task actions - add, edit, delete
        Cursor cursor = getContext().getContentResolver().query(
                DoneListContract.DoneEntry.buildDoneListUri(),                  // URI
                new String[]{DoneListContract.DoneEntry.COLUMN_NAME_ID},        // Projection
                DoneListContract.DoneEntry.COLUMN_NAME_IS_LOCAL + " IS 'TRUE' OR " + // Selection
                        DoneListContract.DoneEntry.COLUMN_NAME_IS_DELETED + " IS 'TRUE' OR " +
                        DoneListContract.DoneEntry.COLUMN_NAME_EDITED_FIELDS + " IS NOT NULL",
                null, // Selection Args
                null  // Sort Order
        );
        
        if (cursor != null && cursor.getCount() > 0) {
            Log.v(LOG_TAG, "Found " + cursor.getCount() + " unsent tasks, post them before fetching");
            
            // Check to prevent cyclicity
            if (!fetchTeamsFlag) {
                new DeleteDonesTask(getContext()).execute(true);
            }
            
            cursor.close();
            return;
        }
        
        // 3. Refresh task list
        if (teamCount > 0)
            fetchTasks(authToken);
    }
    
    private int fetchTasks(String authToken) {
        // We're fetching only latest 100 entries. May change in future to import whole history by iterating till previous == null
        final int tasksToFetch = 100;
        final String requestURL = "https://idonethis.com/api/v0.1/dones/?page_size=" + tasksToFetch + "&done_date_after=";
        HttpURLConnection httpcon = null;
        BufferedReader br = null;
        Context context = getContext();
        
        Utils.sendMessage(context, Utils.SENDER_FETCH_TASKS, "Starting fetch... ", Utils.STATUS_TASK_STARTED, -1);
        
        int resultStatus;
        int fetchedTaskCount = -1;
        
        // Contains server response (or error message)
        String result = "";
        
        try {
            //Connect
            httpcon = (HttpURLConnection) ((new URL(requestURL).openConnection()));
            httpcon.setRequestProperty("Authorization", "Token " + authToken);
            httpcon.setRequestProperty("Content-Type", "application/json");
            httpcon.setRequestProperty("Accept", "application/json");
            httpcon.setRequestMethod("GET");
            httpcon.connect();
            
            //Response Code
            resultStatus = httpcon.getResponseCode();
            String responseMessage = httpcon.getResponseMessage();
            
            switch (resultStatus) {
                case HttpURLConnection.HTTP_ACCEPTED:
                case HttpURLConnection.HTTP_CREATED:
                case HttpURLConnection.HTTP_OK:
                    Log.v(LOG_TAG, "Got Done List - " + resultStatus + ": " + responseMessage);
                    
                    break; // fine
                
                case HttpURLConnection.HTTP_UNAUTHORIZED:
                    Log.w(LOG_TAG, "Authcode invalid - " + resultStatus + ": " + responseMessage);
                    // Set token invalid
                    Utils.setTokenValidity(context, false);
                    Utils.sendMessage(context, Utils.SENDER_FETCH_TASKS, responseMessage, Utils.STATUS_TASK_UNAUTH, -1);
                
                default:
                    Log.w(LOG_TAG, "Couldn't fetch dones" + resultStatus + ": " + responseMessage);
                    Utils.sendMessage(context, Utils.SENDER_FETCH_TASKS, responseMessage, Utils.STATUS_TASK_OTHER_ERROR, -1);
            }
            
            //Read      
            br = new BufferedReader(new InputStreamReader(httpcon.getInputStream(), "UTF-8"));
            
            String line;
            StringBuilder sb = new StringBuilder();
            
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            
            result = sb.toString();
            
            if (result.equals("")) {
                
                result = null;
                
            } else if (resultStatus == HttpURLConnection.HTTP_OK) {
                        
                /*
                * Not to be used till we can get all updates from server, including deletes
                * 
                * */
                // Update lastUpdate timestamp in SharedPrefs in case another fetchDone is triggered
                //String lastUpdated = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.UK).format(new Date());
                //Utils.setLastUpdated(context, lastUpdated);
                //Log.v(LOG_TAG, "New LastUpdated Time: " + lastUpdated);
                
                fetchedTaskCount = getDoneListFromJson(result);
            }
            
        } catch (Exception e) {
            result = null;
            e.printStackTrace();
            Log.e(LOG_TAG, e.getMessage());
            Utils.sendMessage(context, Utils.SENDER_FETCH_TASKS, e.getMessage(), Utils.STATUS_TASK_OTHER_ERROR, -1);
        } finally {
            if (httpcon != null) {
                httpcon.disconnect();
            }
            if (br != null) {
                try {
                    br.close();
                } catch (final IOException e) {
                    Log.e(LOG_TAG, "Error closing stream", e);
                }
            }
        }
        
        if (fetchedTaskCount == -1)
            Log.v(LOG_TAG, "Message from server: " + result);
        
        Utils.sendMessage(context, Utils.SENDER_FETCH_TASKS, "Fetch Successful.", Utils.STATUS_TASK_SUCCESSFUL, fetchedTaskCount);
        return fetchedTaskCount;
    }
    
    
    private int fetchTeams(String authToken) {
        final String TEAMS_URL = "https://idonethis.com/api/v0.1/teams/?page_size=100";
        HttpURLConnection httpcon = null;
        BufferedReader br = null;
        int resultStatus;
        String result = "";
        int teamCount = 0;
        Context context = getContext();
        
        Utils.sendMessage(context, Utils.SENDER_FETCH_TEAMS, "Starting fetch... ", Utils.STATUS_TASK_STARTED, -1);
        
        try {
            //Connect
            httpcon = (HttpURLConnection) ((new URL(TEAMS_URL).openConnection()));
            httpcon.setRequestProperty("Authorization", "Token " + authToken);
            httpcon.setRequestProperty("Content-Type", "application/json");
            httpcon.setRequestProperty("Accept", "application/json");
            httpcon.setRequestMethod("GET");
            httpcon.connect();
            
            //Response Code
            resultStatus = httpcon.getResponseCode();
            String responseMessage = httpcon.getResponseMessage();
            
            switch (resultStatus) {
                case HttpURLConnection.HTTP_OK:
                    Log.v(LOG_TAG, "Got teams - " + resultStatus + ": " + responseMessage);
                    
                    //Read      
                    br = new BufferedReader(new InputStreamReader(httpcon.getInputStream(), "UTF-8"));
                    
                    String line;
                    StringBuilder sb = new StringBuilder();
                    
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    
                    result = sb.toString();
                    
                    httpcon.disconnect();
                    br.close();
                    
                    if (result.equals("")) {
                        
                        result = null;
                        
                    } else {
                        
                        teamCount = getTeamsFromJson(result);
                        
                    }
                    break; // fine
                
                case HttpURLConnection.HTTP_UNAUTHORIZED:
                    Log.w(LOG_TAG, " Authcode invalid - " + resultStatus + ": " + responseMessage);
                    // Set invalid token
                    Utils.setTokenValidity(context, false);
                    Utils.sendMessage(context, Utils.SENDER_FETCH_TEAMS, responseMessage, Utils.STATUS_TASK_UNAUTH, -1);
                    teamCount = -1;
                
                default:
                    Log.w(LOG_TAG, "Couldn't fetch teams - " + resultStatus + ": " + responseMessage);
                    Utils.sendMessage(context, Utils.SENDER_FETCH_TEAMS, responseMessage, Utils.STATUS_TASK_OTHER_ERROR, -1);
                    teamCount = -1;
            }
            
        } catch (Exception e) {
            
            result = null;
            e.printStackTrace();
            Log.e(LOG_TAG, e.getMessage());
            Utils.sendMessage(context, Utils.SENDER_FETCH_TEAMS, e.getMessage(), Utils.STATUS_TASK_OTHER_ERROR, -1);
            
        } finally {
            
            if (httpcon != null) {
                httpcon.disconnect();
            }
            if (br != null) {
                try {
                    br.close();
                } catch (final IOException e) {
                    Log.e(LOG_TAG, "Error closing stream", e);
                }
            }
            
        }
        
        if (teamCount == 0)
            Log.v(LOG_TAG, "Message from server: " + result);
        
        return teamCount;
    }
    
    
    /**
     * Take the String representing the complete forecast in JSON Format and
     * pull out the data we need to construct the Strings needed for the wireframes.
     *
     * Fortunately parsing is easy:  constructor takes the JSON string and converts it
     * into an Object hierarchy for us.
     */
    private int getDoneListFromJson(String forecastJsonStr) throws JSONException {
        
        // Now we have a String representing the complete forecast in JSON Format.
        // Fortunately parsing is easy:  constructor takes the JSON string and converts it
        // into an Object hierarchy for us.
        
        // These are the names of the JSON objects that need to be extracted.
        
        Gson gson = new Gson();
        String doneItemString;
        DoneItem doneItem;
        
        JSONObject masterObj = new JSONObject(forecastJsonStr);
        JSONArray donesListArray = masterObj.getJSONArray("results");
        
        // Insert the new weather information into the database
        Vector<ContentValues> cVVector = new Vector<>(donesListArray.length());
        
        for (int i = 0; i < donesListArray.length(); i++) {
            doneItemString = donesListArray.getJSONObject(i).toString();
            
            doneItem = gson.fromJson(doneItemString, DoneItem.class);
            
            ContentValues doneItemValues = new ContentValues();
            
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_ID, doneItem.id);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_CREATED, doneItem.created);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_UPDATED, doneItem.updated);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_MARKEDUP_TEXT, doneItem.markedup_text);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_DONE_DATE, doneItem.done_date);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_OWNER, doneItem.owner);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_TEAM_SHORT_NAME, doneItem.team_short_name);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_TAGS, gson.toJson(doneItem.tags, DoneItem.DoneTags[].class));
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_LIKES, "");
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_COMMENTS, "");
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_META_DATA, gson.toJson(doneItem.meta_data, DoneItem.DoneMeta.class));
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_IS_GOAL, doneItem.is_goal);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_GOAL_COMPLETED, doneItem.goal_completed);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_URL, doneItem.url);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_TEAM, doneItem.team);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_RAW_TEXT, Html.fromHtml(doneItem.raw_text).toString());
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_PERMALINK, doneItem.permalink);
            doneItemValues.put(DoneListContract.DoneEntry.COLUMN_NAME_IS_LOCAL, doneItem.is_local);
            
            cVVector.add(doneItemValues);
        }
        
        Log.v(LOG_TAG, "Fetched " + cVVector.size() + " tasks");
        
        // add to database
        if (cVVector.size() > 0) {
            ContentValues[] cvArray = new ContentValues[cVVector.size()];
            cVVector.toArray(cvArray);
            
            /*
            * To be used only till we can get all updates from server, including deletes
            * 
            * */
            // Delete all dones from local, to be replaced by freshly retrieved ones 
            // (shouldn't have gotten this far if there were unposted dones)
            getContext().getContentResolver().delete(DoneListContract.DoneEntry.CONTENT_URI, null, null);
            
            // Add newly fetched entries to the server
            getContext().getContentResolver().bulkInsert(DoneListContract.DoneEntry.CONTENT_URI, cvArray);
            
        }
        
        return cVVector.size();
    }
    
    /**
     * Take the String representing the complete forecast in JSON Format and
     * pull out the data we need to construct the Strings needed for the wireframes.
     *
     * Fortunately parsing is easy:  constructor takes the JSON string and converts it
     * into an Object hierarchy for us.
     */
    private int getTeamsFromJson(String teamsJsonStr) throws JSONException {
        
        // Now we have a String representing the complete forecast in JSON Format.
        // Fortunately parsing is easy:  constructor takes the JSON string and converts it
        // into an Object hierarchy for us.
        
        // These are the names of the JSON objects that need to be extracted.
        
        Gson gson = new Gson();
        String teamItemString;
        TeamItem teamItem;
        List<String> teams = new ArrayList<>();
        Context context = getContext();
        
        JSONObject masterObj = new JSONObject(teamsJsonStr);
        JSONArray teamsListArray = masterObj.getJSONArray("results");
        
        // Insert the new weather information into the database
        Vector<ContentValues> cVVector = new Vector<>(teamsListArray.length());
        
        for (int i = 0; i < teamsListArray.length(); i++) {
            teamItemString = teamsListArray.getJSONObject(i).toString();
            
            teamItem = gson.fromJson(teamItemString, TeamItem.class);
            
            teams.add(teamItem.url);
            
            ContentValues teamItemValues = new ContentValues();
            
            teamItemValues.put(DoneListContract.TeamEntry.COLUMN_NAME_URL, teamItem.url);
            teamItemValues.put(DoneListContract.TeamEntry.COLUMN_NAME_NAME, teamItem.name);
            teamItemValues.put(DoneListContract.TeamEntry.COLUMN_NAME_SHORT_NAME, teamItem.short_name);
            teamItemValues.put(DoneListContract.TeamEntry.COLUMN_NAME_DONES, teamItem.dones);
            teamItemValues.put(DoneListContract.TeamEntry.COLUMN_NAME_IS_PERSONAL, teamItem.is_personal);
            teamItemValues.put(DoneListContract.TeamEntry.COLUMN_NAME_PERMALINK, teamItem.permalink);
            
            cVVector.add(teamItemValues);
        }
        
        Log.v(LOG_TAG, "Fetched " + cVVector.size() + " teams");
        
        // add to database
        if (cVVector.size() > 0) {
            
            ContentValues[] cvArray = new ContentValues[cVVector.size()];
            cVVector.toArray(cvArray);
            
            // Add newly fetched teams to the database  
            context.getContentResolver().bulkInsert(DoneListContract.TeamEntry.CONTENT_URI, cvArray);
            
        }
        
        // Set teams in sharedPrefs for colouring
        Utils.setTeams(context, teams.toArray(new String[teams.size()]));
        
        String defaultTeam = Utils.getDefaultTeam(context);
        
        if (defaultTeam == null) {
            // No default team set, so set first team as default
            Utils.setDefaultTeam(context, teams.get(0));
            
        } else if (Utils.findTeam(context, defaultTeam) == -1) {
            // Previous default team not in fetched teams. Else raise error!
            Utils.setDefaultTeam(context, teams.get(0));
            Utils.sendMessage(context, Utils.SENDER_FETCH_TEAMS, "Default team not in teams", Utils.STATUS_TASK_OTHER_ERROR, -1);
        }
        
        // Return number of fetched teams
        return cVVector.size();
    }
    
    
}