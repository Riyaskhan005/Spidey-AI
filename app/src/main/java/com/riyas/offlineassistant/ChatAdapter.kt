package com.riyas.offlineassistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

private const val TYPE_USER = 0
private const val TYPE_AI = 1

class ChatAdapter(
    private val messages: MutableList<ChatMessage> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class UserVH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.messageText)
    }

    class AiVH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.messageText)
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].isUser) TYPE_USER else TYPE_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            UserVH(inflater.inflate(R.layout.item_message_user, parent, false))
        } else {
            AiVH(inflater.inflate(R.layout.item_message_ai, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is UserVH -> holder.text.text = msg.text
            is AiVH -> holder.text.text = msg.text.ifEmpty { "…" }
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: ChatMessage): Int {
        messages.add(message)
        val index = messages.size - 1
        notifyItemInserted(index)
        return index
    }

    /** Updates the text of the message at [index], e.g. while a streaming reply grows. */
    fun updateMessage(index: Int, newText: String) {
        if (index !in messages.indices) return
        messages[index] = messages[index].copy(text = newText)
        notifyItemChanged(index)
    }

    fun removeMessage(index: Int) {
        if (index !in messages.indices) return
        messages.removeAt(index)
        notifyItemRemoved(index)
    }
}
