package com.cowork.project.global.client

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam

@FeignClient(name = "cowork-preference")
interface PreferenceClient {

    @GetMapping("/preferences/github-repo/{repoId}")
    fun getGithubRepoSettings(@PathVariable repoId: Long): Map<String, Any>

    /** ids는 콤마로 구분된 레포 연결 id 목록 (예: "1,2,3") */
    @GetMapping("/preferences/github-repo")
    fun getGithubRepoSettingsBulk(@RequestParam ids: String): Map<String, Map<String, Any>>

    @PutMapping("/preferences/github-repo/{repoId}")
    fun updateGithubRepoSettings(@PathVariable repoId: Long, @RequestBody body: Map<String, Any>): Map<String, Any>
}
