package com.nuvio.tv.ui.navigation

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: the session owner is what the NavHost composes under, and Hilt's hiltViewModel() builds its
 * factory from the owner's HasDefaultViewModelProviderFactory. A bare ViewModelStoreOwner silently falls
 * back to the reflection factory, so every @HiltViewModel (CleanLiveIngressViewModel) crashed at launch.
 * JUnit order: assertEquals(message, expected, actual).
 */
class NavSessionViewModelStoreOwnerTest {

    private class NeedsArgs(val value: String) : ViewModel()

    private class Parent : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                NeedsArgs("from-parent-factory") as T
        }
        val extras: CreationExtras = MutableCreationExtras()
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory get() = factory
        override val defaultViewModelCreationExtras: CreationExtras get() = extras
    }

    @Test
    fun theSessionOwnerExposesTheParentsDefaultFactoryAndExtras() {
        val parent = Parent()
        val owner = NavSessionViewModelStoreOwner(parent, NavSessionViewModelStoreHolder())
        assertTrue("must implement HasDefaultViewModelProviderFactory", owner is HasDefaultViewModelProviderFactory)
        owner as HasDefaultViewModelProviderFactory
        assertSame("factory", parent.factory, owner.defaultViewModelProviderFactory)
        assertSame("extras", parent.extras, owner.defaultViewModelCreationExtras)
    }

    @Test
    fun aViewModelNeedingTheParentFactoryIsCreatedThroughItInTheSessionStore() {
        val parent = Parent()
        val holder = NavSessionViewModelStoreHolder()
        val owner = NavSessionViewModelStoreOwner(parent, holder)
        val viewModel = ViewModelProvider.create(owner)[NeedsArgs::class.java]
        assertEquals("created by the parent factory", "from-parent-factory", viewModel.value)
        assertSame("stored in the session store, not the parent's", viewModel, holder.viewModelStore.get("androidx.lifecycle.ViewModelProvider.DefaultKey:" + NeedsArgs::class.java.canonicalName))
        assertEquals("parent store untouched", null, parent.viewModelStore.get("androidx.lifecycle.ViewModelProvider.DefaultKey:" + NeedsArgs::class.java.canonicalName))
        assertNotNull(viewModel)
    }
}
