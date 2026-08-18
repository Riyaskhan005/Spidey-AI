package com.riyas.SpideyAssistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon

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
        val actionsContainer: View = view.findViewById(R.id.actionsContainer)
        val copyButton: ImageView = view.findViewById(R.id.copyButton)
        val copyCodeButton: ImageView = view.findViewById(R.id.copyCodeButton)
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].isUser) TYPE_USER else TYPE_AI

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {

        val inflater = LayoutInflater.from(parent.context)

        return if (viewType == TYPE_USER) {
            UserVH(
                inflater.inflate(
                    R.layout.item_message_user,
                    parent,
                    false
                )
            )
        } else {
            AiVH(
                inflater.inflate(
                    R.layout.item_message_ai,
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int
    ) {
        val msg = messages[position]
        val context = holder.itemView.context

        when (holder) {

            is UserVH -> {
                holder.text.text = msg.text
            }

            is AiVH -> {
                val text = msg.text.ifEmpty { "…" }

                val markwon = Markwon.create(holder.text.context)
                markwon.setMarkdown(
                    holder.text,
                    text
                )

                val hasContent = msg.text.isNotBlank()
                holder.actionsContainer.visibility = if (hasContent && !msg.isStreaming) View.VISIBLE else View.GONE

                val codeBlocks = extractCodeBlocks(msg.text)
                if (codeBlocks.isNotEmpty() && !msg.isStreaming) {
                    holder.copyCodeButton.visibility = View.VISIBLE
                    holder.copyCodeButton.setOnClickListener {
                        val codeToCopy = codeBlocks.joinToString("\n\n")
                        copyToClipboard(context, codeToCopy, "Code copied to clipboard")
                    }
                } else {
                    holder.copyCodeButton.visibility = View.GONE
                }

                holder.copyButton.setOnClickListener {
                    copyToClipboard(context, msg.text, "Copied to clipboard")
                }
            }
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
    fun updateMessage(index: Int, newText: String, isStreaming: Boolean = false) {
        if (index !in messages.indices) return

        messages[index] = messages[index].copy(
            text = newText,
            isStreaming = isStreaming
        )

        notifyItemChanged(index)
    }

    fun removeMessage(index: Int) {
        if (index !in messages.indices) return
        messages.removeAt(index)
        notifyItemRemoved(index)
    }

    private fun copyToClipboard(context: Context, text: String, toastMessage: String) {
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Spidey AI", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }

    private fun extractCodeBlocks(markdown: String): List<String> {
        val list = mutableListOf<String>()
        val regex = Regex("```(?:[a-zA-Z0-9_-]+)?\n?([\\s\\S]*?)```")
        val matches = regex.findAll(markdown)
        for (match in matches) {
            val code = match.groups[1]?.value?.trim()
            if (!code.isNullOrEmpty()) {
                list.add(code)
            }
        }
        return list
    }
}
