package com.moonkata.textreader.data.sync

import com.moonkata.textreader.BuildConfig

/**
 * Supabase project coordinates for VSCode reading-position sync (.docs/VSCODE_SYNC_PLAN.md §1).
 *
 * The values themselves aren't secret — under Supabase's newer key scheme, the publishable key is by
 * design meant to be exposed in the client bundle (the actual defense is the RLS policy), so committing
 * it directly to source is safe. Still, to avoid leaving it permanently in a public repo's history, this
 * simply exposes the `BuildConfig` field injected via `local.properties`/CI environment variables
 * (`app/build.gradle.kts`, SYNC_MULTIUSER_PLAN.md stage 3). If the value isn't injected it's an empty
 * string, in which case only the VSCode sync feature is silently disabled — the build itself always
 * succeeds. The actual shared secret that must be protected isn't here; the user enters it directly in
 * the settings screen.
 */
object SupabaseConfig {
    val URL: String = BuildConfig.SUPABASE_URL
    val PUBLISHABLE_KEY: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY
}
