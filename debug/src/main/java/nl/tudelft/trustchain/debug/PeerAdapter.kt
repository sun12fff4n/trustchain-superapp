package nl.tudelft.trustchain.debug

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PeerAdapter(private val peers: List<PeerItem>) :
    RecyclerView.Adapter<PeerAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ipAddress: TextView = itemView.findViewById(R.id.ipAddress)
        val lastResponse: TextView = itemView.findViewById(R.id.lastResponse)
        val timeourAlert: ImageView = itemView.findViewById(R.id.timeoutAlert)
        val publicKey: TextView = itemView.findViewById(R.id.publicKey)
//        val alertIndicator: ImageView = itemView.findViewById(R.id.alertIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer_status, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val peer = peers[position]
        holder.ipAddress.text = peer.ip
        holder.lastResponse.text = peer.lastResponseTime
        holder.timeourAlert.visibility =  if (peer.isTimeOut) View.VISIBLE else View.INVISIBLE
        holder.publicKey.text = peer.publicKey
//        holder.alertIndicator.visibility = if (peer.hasAlerts) View.VISIBLE else View.GONE
    }

    override fun getItemCount(): Int = peers.size
}
