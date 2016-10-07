/*
 *    Calendula - An assistant for personal medication management.
 *    Copyright (C) 2016 CITIUS - USC
 *
 *    Calendula is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this software.  If not, see <http://www.gnu.org/licenses/>.
 */

package es.usc.citius.servando.calendula.scheduling;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.LocalTime;

import java.util.List;

import es.usc.citius.servando.calendula.CalendulaApp;
import es.usc.citius.servando.calendula.R;
import es.usc.citius.servando.calendula.activities.ConfirmActivity;
import es.usc.citius.servando.calendula.activities.ReminderNotification;
import es.usc.citius.servando.calendula.database.DB;
import es.usc.citius.servando.calendula.persistence.DailyScheduleItem;
import es.usc.citius.servando.calendula.persistence.Routine;
import es.usc.citius.servando.calendula.persistence.Schedule;
import es.usc.citius.servando.calendula.persistence.ScheduleItem;

/**
 *
 */
public class AlarmScheduler {

    private static final String TAG = "AlarmScheduler";

    public static final String EXTRA_PARAMS = "alarm_params";

    private static final AlarmScheduler instance = new AlarmScheduler();

    private AlarmScheduler() {

    }

    // static method to get the AlarmScheduler instance
    public static AlarmScheduler instance() {
        return instance;
    }

    public static PendingIntent pendingIntent(Context ctx, Routine routine, LocalDate date, boolean delayed, int actionType){
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        AlarmIntentParams params = AlarmIntentParams.forRoutine(routine.getId(), date, delayed, actionType);
        setAlarmParams(intent, params);
        return PendingIntent.getBroadcast(ctx, params.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static PendingIntent pendingIntent(Context ctx, Schedule schedule, LocalTime time, LocalDate date, boolean delayed, int actionType){
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        AlarmIntentParams params = AlarmIntentParams.forSchedule(schedule.getId(), time, date, delayed,actionType);
        setAlarmParams(intent, params);
        return PendingIntent.getBroadcast(ctx, params.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public Long getAlarmRepeatFreq(Context context){
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String delayMinutesStr = prefs.getString("alarm_repeat_frequency", "15");
        return Long.parseLong(delayMinutesStr);
    }

    /**
     * Set an alarm for a routine
     */
    private void setFirstAlarm(Routine routine, LocalDate date, Context ctx) {
        long timestamp = date.toDateTime(routine.time()).getMillis();
        PendingIntent routinePendingIntent = pendingIntent(ctx, routine, date, false, AlarmIntentParams.AUTO);
        setExactAlarm(ctx,timestamp,routinePendingIntent);
    }

    /**
     * Set an alarm for a repeating schedule item
     */
    private void setFirstAlarm(Schedule schedule, LocalTime time, LocalDate date, Context ctx) {
        DateTime dateTime = date.toDateTime(time);
        PendingIntent routinePendingIntent = pendingIntent(ctx, schedule, time, date, false,AlarmIntentParams.AUTO);
        setExactAlarm(ctx,dateTime.getMillis(),routinePendingIntent);
    }

    private void setRepeatAlarm(Routine routine, AlarmIntentParams firstParams, Context ctx, long delayMillis) {
        PendingIntent routinePendingIntent = pendingIntent(ctx, routine, firstParams.date(), true, firstParams.actionType);
        setExactAlarm(ctx,DateTime.now().getMillis() + delayMillis,routinePendingIntent);
    }

    private void setRepeatAlarm(Schedule schedule, AlarmIntentParams firstParams, Context ctx, long delayMillis) {
        PendingIntent schedulePendingIntent = pendingIntent(ctx, schedule, firstParams.scheduleTime(), firstParams.date(), true, firstParams.actionType);
        setExactAlarm(ctx,DateTime.now().getMillis() + delayMillis,schedulePendingIntent);
    }

    private void setExactAlarm(Context ctx, long millis, PendingIntent pendingIntent){
        AlarmManager alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 23)
        {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent);
        } else if (Build.VERSION.SDK_INT >= 19 )
        {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, millis, pendingIntent);
        } else
        {
            alarmManager.set(AlarmManager.RTC_WAKEUP, millis, pendingIntent);
        }
    }

    private void cancelAlarm(Routine routine, LocalDate date, Context ctx)
    {
        PendingIntent routinePendingIntent = pendingIntent(ctx, routine, date, false, AlarmIntentParams.AUTO);
        AlarmManager alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(routinePendingIntent);
        }
    }

    private void cancelDelayedAlarm(Routine routine, LocalDate date, Context ctx, int actionType) {


        if (actionType == AlarmIntentParams.USER){
            // when cancelling reminder, update time taken to now, but don't set as taken
            for (ScheduleItem scheduleItem : routine.scheduleItems()) {
                DailyScheduleItem ds = DailyScheduleItem.findByScheduleItem(scheduleItem);
                ds.setTimeTaken(LocalTime.now());
                ds.save();
                Log.d(TAG, "Set time taken to " + ds.scheduleItem().schedule().medicine().name());

            }
        }
        // cancel alarm
        // get delay routine pending intent
        PendingIntent routinePendingIntent = pendingIntent(ctx, routine, date, true, actionType);
        // Get the AlarmManager service
        AlarmManager alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(routinePendingIntent);
        }
    }

    private void cancelHourlyDelayedAlarm(Schedule s, LocalTime t, LocalDate date, Context ctx, int actionType) {

        DailyScheduleItem ds = DB.dailyScheduleItems().findByScheduleAndTime(s, t);
        if(ds!=null) {

            if(actionType == AlarmIntentParams.USER) {
                ds.setTimeTaken(LocalTime.now());
                ds.save();
            }

            // get hourly delay pending intent
            PendingIntent pendingIntent = pendingIntent(ctx, s, t, date, true,actionType);
            // Get the AlarmManager service
            AlarmManager alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.cancel(pendingIntent);
            }
        }
    }

    private void setAlarmsIfNeeded(Schedule schedule, LocalDate date, Context ctx) {
        if (!schedule.repeatsHourly()) {
            for (ScheduleItem scheduleItem : schedule.items()) {
                if (scheduleItem.routine() != null && canBeScheduled(scheduleItem.routine().time().toDateTimeToday(),ctx)){
                    setFirstAlarm(scheduleItem.routine(), date, ctx);
                }
            }
        } else {
            List<DateTime> times = schedule.hourlyItemsAt(date.toDateTimeAtStartOfDay());
            for (DateTime time : times) {
                if(canBeScheduled(time,ctx)) {
                    setFirstAlarm(schedule, time.toLocalTime(), date, ctx);
                }
            }
        }
    }

    private void onRoutineTime(Routine routine, AlarmIntentParams firstParams, Context ctx) {

        // get the schedule items for the current routine, excluding already taken
        List<ScheduleItem> doses = ScheduleUtils.getRoutineScheduleItems(routine, false);

        Log.d(TAG, "OnRoutineTime - ScheduleItems: " + doses.size());
        if (!doses.isEmpty()) {

            boolean notify = false;
            // check if all items have timeTaken (cancelled notifications)            
            for (ScheduleItem scheduleItem : doses) {
                DailyScheduleItem ds = DailyScheduleItem.findByScheduleItem(scheduleItem);
                if (ds != null && ds.timeTaken() == null) {
                    Log.d(TAG, ds.scheduleItem().schedule().medicine().name() + " not checked or cancelled. Notify!");
                    notify = true;
                    break;
                }
            }

            if (notify) {
                final Intent intent = new Intent(ctx, ConfirmActivity.class);
                intent.putExtra("routine_id", routine.getId());
                intent.putExtra("date", firstParams.date);
                ReminderNotification.notify(ctx, ctx.getResources().getString(R.string.meds_time), routine, doses, intent);
                Log.d(TAG, "Show notification");
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
                boolean repeatAlarms = prefs.getBoolean("alarm_repeat_enabled", false);
                if (repeatAlarms) {
                    firstParams.actionType = AlarmIntentParams.AUTO;
                    setRepeatAlarm(routine, firstParams, ctx, getAlarmRepeatFreq(ctx)*60*1000);
                }
            }
        }
    }

    private void onHourlyScheduleTime(Schedule schedule, AlarmIntentParams firstParams, Context ctx) {

        boolean notify = false;
        // check if this item has timeTaken (cancelled notifications)

        DailyScheduleItem ds = DB.dailyScheduleItems().findBy(schedule, firstParams.date(), firstParams.scheduleTime());
        if (ds != null && ds.timeTaken() == null)
        {
            notify = true;
        }

        if (notify)
        {
            final Intent intent = new Intent(ctx, ConfirmActivity.class);
            intent.putExtra(CalendulaApp.INTENT_EXTRA_SCHEDULE_ID, schedule.getId());
            intent.putExtra(CalendulaApp.INTENT_EXTRA_SCHEDULE_TIME, firstParams.scheduleTime);
            intent.putExtra("date", firstParams.date);

            String title = ctx.getResources().getString(R.string.meds_time);
            ReminderNotification.notify(ctx, title, schedule, firstParams.scheduleTime(), intent);
            // Handle delay if needed
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            boolean repeatAlarms = prefs.getBoolean("alarm_repeat_enabled", false);
            if (repeatAlarms)
            {
                firstParams.actionType = AlarmIntentParams.AUTO;
                setRepeatAlarm(schedule, firstParams, ctx, getAlarmRepeatFreq(ctx) * 60 * 1000);
            }
        }
    }

    private void onRoutineLost(Routine routine, Context ctx) {
        // get the schedule items for the current routine, excluding already taken
        List<ScheduleItem> doseList = ScheduleUtils.getRoutineScheduleItems(routine, true);
        int doses = doseList.size();
        ReminderNotification.cancel(ctx,ReminderNotification.routineNotificationId(routine.getId().intValue()));
        ReminderNotification.lost(doses + " " + ctx.getString(R.string.medicine) + (doses>1?"s":"") + " (" + routine.name() + ")", ctx);
    }

    private void onHourlyScheduleLost(Schedule schedule, Context ctx) {
        ReminderNotification.cancel(ctx,ReminderNotification.scheduleNotificationId(schedule.getId().intValue()));
        ReminderNotification.lost(schedule.medicine().name() + ", " + schedule.toReadableString(ctx), ctx);
    }

    public void onAlarmReceived(AlarmIntentParams params, Context ctx) {

        Routine routine = Routine.findById(params.routineId);
        if (routine != null) {
            Log.d(TAG, "onAlarmReceived: " + routine.getId() + ", " + routine.name());
            if (params.actionType == AlarmIntentParams.USER || isWithinDefaultMargins(routine, params.date(), ctx)) {
                Log.d(TAG, "Alarm received, is user action: " + (params.actionType == AlarmIntentParams.USER));
                onRoutineTime(routine, params, ctx);
            } else {
                onRoutineLost(routine,ctx);
            }
        }
    }

    public void onHourlyAlarmReceived(AlarmIntentParams params, Context ctx) {
        Schedule schedule = Schedule.findById(params.scheduleId);
        if (schedule != null){
            DateTime time = params.dateTime();
            if (params.actionType == AlarmIntentParams.USER || isWithinDefaultMargins(time, ctx)){

                Log.d(TAG, "Alarm received, is user action: " + (params.actionType == AlarmIntentParams.USER));

                onHourlyScheduleTime(schedule, params, ctx);
            } else {
                onHourlyScheduleLost(schedule,ctx);
            }
        }
    }



    public void onDelayRoutine(Routine r, LocalDate date, Context ctx) {
        if (isWithinDefaultMargins(r,date,ctx)) {
            setRepeatAlarm(r, AlarmIntentParams.forRoutine(r.getId(), date, true), ctx, getAlarmRepeatFreq(ctx)* 60 * 1000);
            ReminderNotification.cancel(ctx,ReminderNotification.routineNotificationId(r.getId().intValue()));
        }
    }

    public void onDelayHourlySchedule(Schedule s, LocalTime t, LocalDate date, Context ctx) {
        if (isWithinDefaultMargins(date.toDateTime(t), ctx)) {
            setRepeatAlarm(s, AlarmIntentParams.forSchedule(s.getId(), t, date, true), ctx, getAlarmRepeatFreq(ctx)* 60 * 1000);
            ReminderNotification.cancel(ctx,ReminderNotification.scheduleNotificationId(s.getId().intValue()));
        }
    }

    public void onUserDelayHourlySchedule(Schedule s, LocalTime t, LocalDate date, Context ctx, long delayMinutes) {
        setRepeatAlarm(s, AlarmIntentParams.forSchedule(s.getId(), t, date, true, AlarmIntentParams.USER), ctx, delayMinutes * 60 * 1000);
        cancelHourlyDelayedAlarm(s, t, date, ctx, AlarmIntentParams.AUTO);
        ReminderNotification.cancel(ctx,ReminderNotification.scheduleNotificationId(s.getId().intValue()));
    }

    public void onUserDelayRoutine(Routine r, LocalDate date, Context ctx, int delayMinutes) {
        setRepeatAlarm(r, AlarmIntentParams.forRoutine(r.getId(), date, true, AlarmIntentParams.USER),ctx, delayMinutes*60*1000);
        cancelDelayedAlarm(r, date, ctx, AlarmIntentParams.AUTO);
        ReminderNotification.cancel(ctx,ReminderNotification.routineNotificationId(r.getId().intValue()));
    }

    public void updateAllAlarms(Context ctx) {
        for (Schedule schedule : Schedule.findAll()) {
            setAlarmsIfNeeded(schedule, LocalDate.now(), ctx);
        }
    }

    //
    // Methods to check if a intake is available according to the user preferences
    //

    public boolean isWithinDefaultMargins(Routine r, LocalDate date, Context cxt) {
        return isWithinDefaultMargins(date.toDateTime(r.time()), cxt);
    }

    public static boolean isWithinDefaultMargins(DateTime t, Context cxt) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(cxt);
        String delayMinutesStr = prefs.getString("alarm_reminder_window", "60");
        long window = Long.parseLong(delayMinutesStr);
        DateTime now = DateTime.now();
        return t.isBefore(now) && t.plusMillis((int) window * 60 * 1000).isAfter(now);
    }

    /*
     * Whether an alarm for a specific time can be scheduled or not based on
     * the alarm time and the alarm reminder window defined by the user. Alarm time plus
     * alarm window must be in the future to allow alarm scheduling
     */
    public static boolean canBeScheduled(DateTime t, Context cxt) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(cxt);
        String delayMinutesStr = prefs.getString("alarm_reminder_window", "60");
        int window = (int)Long.parseLong(delayMinutesStr);
        return t.plusMinutes(window).isAfterNow();
    }

    //
    // Methods to cancel status bar notifications and related alarms
    //

    public void cancelStatusBarNotification(Routine r, LocalDate date, Context ctx) {
        Log.d(TAG, "cancelStatusBarNotification: " + r.getId() + ", " + r.name());
        cancelDelayedAlarm(r, date, ctx, AlarmIntentParams.USER);
        ReminderNotification.cancel(ctx,ReminderNotification.routineNotificationId(r.getId().intValue()) );
    }

    public void cancelStatusBarNotification(Schedule r, LocalTime t, LocalDate date, Context ctx) {
        Log.d(TAG, "cancelStatusBarNotification: " + r.getId());
        cancelHourlyDelayedAlarm(r, t, date, ctx, AlarmIntentParams.USER);
        ReminderNotification.cancel(ctx, ReminderNotification.scheduleNotificationId(r.getId().intValue()));
    }

    //
    // Methods called when there are changes in database, to update the alarm status
    //

    public void onCreateOrUpdateRoutine(Routine r, Context ctx) {
        Log.d(TAG, "onCreateOrUpdateRoutine: " + r.getId() + ", " + r.name());
        setFirstAlarm(r, LocalDate.now(), ctx);
    }

    public void onCreateOrUpdateSchedule(Schedule s, Context ctx) {
        Log.d(TAG, "onCreateOrUpdateSchedule: " + s.getId() + ", " + s.medicine().name());
        setAlarmsIfNeeded(s, LocalDate.now(), ctx);
    }

    public void onDeleteRoutine(Routine r, Context ctx) {
        Log.d(TAG, "onDeleteRoutine: " + r.getId() + ", " + r.name());
        cancelAlarm(r,LocalDate.now(), ctx);
    }


    public static void setAlarmParams(Intent intent, AlarmIntentParams parcelable){
        Bundle b = new Bundle();
        b.putParcelable(EXTRA_PARAMS,parcelable);
        intent.putExtra(EXTRA_PARAMS,b);
    }

    public static AlarmIntentParams getAlarmParams(Intent intent){
        // try to get the bundle
        Bundle bundleExtra = intent.getBundleExtra(EXTRA_PARAMS);
        if(bundleExtra!=null){
            return bundleExtra.getParcelable(EXTRA_PARAMS);
        }else{
            // try to get the parcelable from the intent
            return intent.getParcelableExtra(EXTRA_PARAMS);
        }
    }






}
