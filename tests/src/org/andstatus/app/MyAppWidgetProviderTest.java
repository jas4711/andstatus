/*
 * Copyright (C) 2010 yvolk (Yuri Volkov), http://yurivolkov.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.andstatus.app;

import android.content.Intent;
import android.text.format.Time;

import org.andstatus.app.appwidget.MyAppWidgetProvider;
import org.andstatus.app.util.MyLog;

import static org.andstatus.app.MyService.*;

import android.content.*;
import android.text.format.DateUtils;
import android.test.ActivityTestCase;

import java.util.Calendar;

/**
 * Runs various tests...
 * @author yvolk@yurivolkov.com
 */
public class MyAppWidgetProviderTest extends ActivityTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        TestSuite.initialize(this);
        initializeDateTests();
    }
    
    public void test001WidgetTime() throws Exception {
        MyLog.i(this, "testWidgetTime started");
        Context context = MyContextHolder.get().context();
    	
    	MyAppWidgetProvider widget = new MyAppWidgetProvider();
    	MyLog.i(this, "MyAppWidgetProvider created");

    	/*
    	long startMillis = 1267968833922l;
    	long endMillis = 1267968834922l;
    	
    	widgetTime = "3/7/10";
        assertEquals("Widget time is not equal for " + widgetTime, widgetTime, widget.formatWidgetTime(targetContext, startMillis, endMillis));

        Time time = new Time();
    	time.set(1, 1, 10, 8, 3, 2010);
    	startMillis = time.toMillis(false);
    	time.setToNow();
    	endMillis = time.toMillis(false);
    	widgetTime = "4/8/10 - 4:01 PM";
        assertEquals("Widget time is not equal for " + widgetTime, widgetTime, widget.formatWidgetTime(targetContext, startMillis, endMillis));
        */
    	
        int len = dateTests.length;
        for (int index = 0; index < len; index++) {
            DateTest dateTest = dateTests[index];
            if (dateTest == null) { 
            	break; 
            }
            long startMillis = dateTest.date1.toMillis(false /* use isDst */);
            long endMillis = dateTest.date2.toMillis(false /* use isDst */);
            int flags = dateTest.flags;
            String output = DateUtils.formatDateRange(context, startMillis, endMillis, flags);
            /*
            if (!dateTest.expectedOutput.equals(output)) {
                Log.i("FormatDateRangeTest", "index " + index
                        + " expected: " + dateTest.expectedOutput
                        + " actual: " + output);
            } */
            
            String output2 = widget.formatWidgetTime(context, startMillis, endMillis);
        	MyLog.i(this, "\"" + output + "\"; \"" + output2 + "\"");
            
            //assertEquals(dateTest.expectedOutput, output);
        }         
    }   

    DateTest[] dateTests = new DateTest[101];
    
    static private class DateTest {
        public Time date1;
        public Time date2;
        public int flags;
        
        public DateTest(long startMillis, long endMillis) {
        	date1 = new Time();
        	date1.set(startMillis);
        	date2 = new Time();
        	date2.set(endMillis);
        	flags = DateUtils.FORMAT_24HOUR | DateUtils.FORMAT_SHOW_DATE 
        	| DateUtils.FORMAT_SHOW_TIME;
        }
    }
    
    private void initializeDateTests() {
    	// Initialize dateTests
    	int ind = 0;
    	Calendar cal1 = Calendar.getInstance();
    	Calendar cal2 = Calendar.getInstance();

    	cal1.setTimeInMillis(System.currentTimeMillis());
    	cal2.setTimeInMillis(System.currentTimeMillis());
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());
    	
    	ind += 1;
    	cal1.roll(Calendar.SECOND, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());

    	ind += 1;
    	cal1.roll(Calendar.SECOND, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());
    	
    	ind += 1;
    	cal1.roll(Calendar.MINUTE, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());

    	ind += 1;
    	cal1.add(Calendar.SECOND, 5);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());

    	ind += 1;
    	cal1.roll(Calendar.MINUTE, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());

    	ind += 1;
    	cal1.roll(Calendar.HOUR, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());

    	ind += 1;
    	cal1.roll(Calendar.HOUR, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());
    	
    	ind += 1;
    	cal1.roll(Calendar.DAY_OF_YEAR, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());
    	
    	ind += 1;
    	cal1.roll(Calendar.DAY_OF_YEAR, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());

    	ind += 1;
    	cal2.roll(Calendar.MINUTE, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());

    	ind += 1;
    	cal2.roll(Calendar.HOUR, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());

    	ind += 1;
    	cal2.roll(Calendar.HOUR, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());

    	ind += 1;
    	cal1.roll(Calendar.YEAR, false);
    	dateTests[ind] = new DateTest(cal1.getTimeInMillis(), cal2.getTimeInMillis());
    }

    public void test100Receiver() throws Exception {
    	MyLog.i(this, "testReceiver started");

    	int numTweets;
    	CommandEnum msgType;
    	
    	numTweets = 1;
    	msgType = CommandEnum.NOTIFY_MENTIONS;
    	updateWidgets(numTweets, msgType);
    	
    	numTweets = 1;
    	msgType = CommandEnum.NOTIFY_DIRECT_MESSAGE;
    	updateWidgets(numTweets, msgType);
    	
    	numTweets = 1;
    	msgType = CommandEnum.NOTIFY_HOME_TIMELINE;
    	updateWidgets(numTweets, msgType);
    	
    	// Some seconds to complete updates
    	// Shorter period sometimes doesn't work (processes are being closed...)
    	Thread.sleep(1000);
    }
    
	/** 
	 * Send Update intent to AndStatus Widget(s),
	 * if there are some installed... (e.g. on the Home screen...) 
	 * @see MyAppWidgetProvider
	 */
	private void updateWidgets(int numTweets, CommandEnum msgType){
		try {
		updateWidgetsNow(numTweets, msgType);
		//updateWidgetsThreads(numHomeTimeline, msgType);
		//updateWidgetsPending(numHomeTimeline, msgType);
		} catch (Exception e) {
			
		}
	}

    
	/** 
	 * Send Update intent to AndStatus Widget(s),
	 * if there are some installed... (e.g. on the Home screen...) 
	 * @see MyAppWidgetProvider
	 */
	private void updateWidgetsNow(int numTweets, CommandEnum msgType){
    	Context context = this.getInstrumentation().getContext();
    	//Context context = getInstrumentation().getContext();

    	MyLog.i(this, "Sending update; numHomeTimeline=" + numTweets + "; msgType=" + msgType);

    	Intent intent = new Intent(ACTION_APPWIDGET_UPDATE);
		intent.putExtra(IntentExtra.EXTRA_NUMTWEETS.key, numTweets);
		intent.putExtra(IntentExtra.EXTRA_MSGTYPE.key, msgType.save());
		context.sendBroadcast(intent);
    	
	}
}