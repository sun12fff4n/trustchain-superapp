package nl.tudelft.trustchain.currencyii.ui.raft

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import nl.tudelft.ipv8.Peer
import nl.tudelft.trustchain.currencyii.R
import nl.tudelft.trustchain.currencyii.util.frost.raft.RaftElectionModule.NodeState
import java.text.SimpleDateFormat
import java.util.*

// Adapter for displaying peers in a RecyclerView
class PeerAdapter(
    private val peers: MutableList<PeerItem> = mutableListOf()
) : RecyclerView.Adapter<PeerAdapter.ViewHolder>() {

    data class PeerItem(
        val peer: Peer,
        var isInRaft: Boolean = false,
        var raftState: NodeState? = null,
        var lastSeen: Date = Date()
    )

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textPeerId: TextView = view.findViewById(R.id.textPeerId)
        val textPeerAddress: TextView = view.findViewById(R.id.textPeerAddress)
        val textPeerStatus: TextView = view.findViewById(R.id.textPeerStatus)
        val textLastSeen: TextView = view.findViewById(R.id.textLastSeen)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val peerItem = peers[position]
        holder.textPeerId.text = "Peer: ${peerItem.peer.mid}"
        holder.textPeerAddress.text = "Address: ${peerItem.peer.address}"

        val status = when {
            !peerItem.isInRaft -> "Not in Raft"
            peerItem.raftState == NodeState.LEADER -> "LEADER"
            peerItem.raftState == NodeState.CANDIDATE -> "CANDIDATE"
            peerItem.raftState == NodeState.FOLLOWER -> "FOLLOWER"
            else -> "Unknown"
        }
        holder.textPeerStatus.text = "Status: $status"

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        holder.textLastSeen.text = "Last seen: ${sdf.format(peerItem.lastSeen)}"
    }

    override fun getItemCount() = peers.size

    fun updatePeer(peer: Peer, isInRaft: Boolean = false, raftState: NodeState? = null) {
        val existingIndex = peers.indexOfFirst { it.peer.mid == peer.mid }
        if (existingIndex >= 0) {
            peers[existingIndex].isInRaft = isInRaft
            peers[existingIndex].raftState = raftState
            peers[existingIndex].lastSeen = Date()
            notifyItemChanged(existingIndex)
        } else {
            peers.add(PeerItem(peer, isInRaft, raftState))
            notifyItemInserted(peers.size - 1)
        }
    }
}
