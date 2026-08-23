package com.cowork.project.global.client

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody

@FeignClient(name = "cowork-preference")
interface PreferenceClient {

    @GetMapping("/preferences/github-repo/{repoId}")
    fun getGithubRepoSettings(@PathVariable repoId: Long): Map<String, Any>

    @PutMapping("/preferences/github-repo/{repoId}")
    fun updateGithubRepoSettings(@PathVariable repoId: Long, @RequestBody body: Map<String, Any>): Map<String, Any>
}
