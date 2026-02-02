package com.synapse.service

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InputDispatcherTest {

    private lateinit var context: Context
    private lateinit var dispatcher: InputDispatcher

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        dispatcher = InputDispatcher(context)
    }

    @Test
    fun `vibrateForRegionSelection does not throw`() {
        // In test environment, vibration may not work but should not crash
        dispatcher.vibrateForRegionSelection()
    }

    @Test
    fun `vibrateForRegionSelection can be called multiple times`() {
        dispatcher.vibrateForRegionSelection()
        dispatcher.vibrateForRegionSelection()
        dispatcher.vibrateForRegionSelection()
    }
}
