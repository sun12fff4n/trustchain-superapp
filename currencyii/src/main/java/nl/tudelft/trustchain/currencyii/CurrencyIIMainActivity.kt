package nl.tudelft.trustchain.currencyii

import android.util.Log
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import nl.tudelft.trustchain.currencyii.ui.raft.RaftTestActivity
import nl.tudelft.trustchain.common.BaseActivity

class CurrencyIIMainActivity : BaseActivity() {
    private var topLevelDestinationIds = setOf(R.id.blockchainDownloadFragment, R.id.daoLoginChoice)

    override val navigationGraph = R.navigation.nav_graph
    override val bottomNavigationMenu = R.menu.currencyii_bottom_navigation_menu

    override val appBarConfiguration by lazy {
        AppBarConfiguration(topLevelDestinationIds)
    }

    override fun onBackPressed() {
        val currentFragment = findNavController(R.id.navHostFragment).currentDestination
        val currentDestinationId = currentFragment?.id
        if (topLevelDestinationIds.contains(currentDestinationId)) {
            // Do not allow hardware back press on any top level destinations.
            Log.i("Coin", "Hardware back press not allowed on top level destinations.")
        } else {
            super.onBackPressed()
        }
    }

//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        // 在已有代码的基础上添加
//        findNavController(R.id.navHostFragment).addOnDestinationChangedListener { _, destination, _ ->
//            if (destination.id == R.id.raftTestActivity) {
//                startActivity(Intent(this, RaftTestActivity::class.java))
//            }
//        }
//    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.raftTestFragment -> {
                startActivity(Intent(this, RaftTestActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    fun addTopLevelDestinationId(id: Int) {
        val topLevelDestinationIdsList = topLevelDestinationIds.toMutableList()
        if (!topLevelDestinationIdsList.contains(id)) {
            topLevelDestinationIdsList.add(id)
            topLevelDestinationIds = topLevelDestinationIdsList.toSet()
        }
    }

    fun removeTopLevelDestinationId(id: Int) {
        val topLevelDestinationIdsList = topLevelDestinationIds.toMutableList()
        if (topLevelDestinationIdsList.contains(id)) {
            topLevelDestinationIdsList.remove(id)
            topLevelDestinationIds = topLevelDestinationIdsList.toSet()
        }
    }
}
