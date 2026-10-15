package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.TouchLockPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Touch Lock", appName)
  }

  @Test
  fun `preferences default idle timeout is 10 seconds`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = TouchLockPreferences(context)
    assertEquals(10, prefs.idleTimeoutSeconds)
    assertTrue(prefs.isVibrationEnabled)
  }
}
