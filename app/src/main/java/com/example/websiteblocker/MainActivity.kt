package com.example.websiteblocker

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var repo: BlocklistRepository
    private lateinit var adapter: ArrayAdapter<String>

    private val vpnPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpnService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = BlocklistRepository(this)

        val toggle = findViewById<Switch>(R.id.vpnToggle)
        val input = findViewById<EditText>(R.id.domainInput)
        val addBtn = findViewById<Button>(R.id.addButton)
        val list = findViewById<ListView>(R.id.domainList)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, repo.getAll().toMutableList())
        list.adapter = adapter

        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) requestVpnPermission() else stopVpnService()
        }

        addBtn.setOnClickListener {
            val domain = input.text.toString().trim()
            if (domain.isNotEmpty()) {
                repo.add(domain)
                refreshList()
                input.text.clear()
            }
        }

        list.setOnItemLongClickListener { _, _, position, _ ->
            val domain = adapter.getItem(position) ?: return@setOnItemLongClickListener false
            repo.remove(domain)
            refreshList()
            true
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent)
        else startVpnService()
    }

    private fun startVpnService() {
        startService(Intent(this, BlockerVpnService::class.java))
    }

    private fun stopVpnService() {
        startService(Intent(this, BlockerVpnService::class.java).apply {
            action = BlockerVpnService.ACTION_STOP
        })
    }

    private fun refreshList() {
        adapter.clear()
        adapter.addAll(repo.getAll())
        adapter.notifyDataSetChanged()
    }
}
