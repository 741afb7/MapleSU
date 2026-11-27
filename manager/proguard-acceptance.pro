# The test runner resolves this shared dependency before its first test runs.
-keep class androidx.tracing.Trace { *; }

# Instrumentation reuses the target APK runtime, including its base classes.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# These production types form the ABI of the three SDK acceptance tests.
# Test mapping preserves names, not target-side optimizations such as object
# staticization or removed getters; retain their callable shape in this variant.
-keep class dev.maplesu.manager.modules.UltraRootKeyStore { *; }
-keep class dev.maplesu.manager.ultrakernel.UltraKernelCredential { *; }
-keep class dev.maplesu.manager.ultrakernel.UltraKernelCredential$Companion { *; }
-keep class dev.maplesu.manager.runtime.ultra.IdentityVerification { *; }
-keep class dev.maplesu.manager.runtime.ultra.InstalledIdentity { *; }
-keep class dev.maplesu.manager.runtime.ultra.NativeReceipt { *; }
-keep class dev.maplesu.manager.runtime.ultra.UltraEnvironmentInstaller { *; }
-keep class dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe { *; }
-keep class dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe$State { *; }
-keep class dev.maplesu.manager.runtime.ultra.UltraIdentityRecovery { *; }
-keep class dev.maplesu.manager.runtime.ultra.UltraInstalledIdentityProbe { *; }
-keep class dev.maplesu.manager.runtime.ultra.UltraInstalledIdentityProbe$Readback { *; }
-keep class dev.maplesu.manager.runtime.ultra.UltraRuntimeClient { *; }
-keep class dev.maplesu.manager.runtime.ultra.Source { *; }
-keep class dev.maplesu.manager.workmode.ModeRuntime { *; }
-keep class dev.maplesu.manager.workmode.WorkMode { *; }

# Original-page WebUI instrumentation reads the active FPT background and restores UI mode.
-keep class com.maplesu.core.theme.ThemeRuntime { *; }
-keep class com.maplesu.core.ui.util.AppearancePreferences { *; }

# Explicit ABI used by the complete Release AndroidTest callers.
# Preserve their actual fields and callable names, including overloads, constructors and default bridges.
# Names remain eligible for the shared R8 mapping; other production code stays optimized.
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.KernelSUApplication
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.KernelSUApplicationKt {
    *** setKsuApp(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.KernelVersion {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.Natives {
    *** INSTANCE;
    *** getAppProfile(...);
    *** getKernelUAPIVersion(...);
    *** getSuperuserCount(...);
    *** getVersion(...);
    *** isFullFeatured(...);
    *** isManager(...);
    *** isSuEnabled(...);
    *** setAppProfile(...);
    *** setSelinuxHideEnabled(...);
    *** setSuEnabled(...);
    *** stealthState(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.Natives$Profile {
    <init>(...);
    *** copy$default(...);
    *** getAllowSu(...);
    *** getCurrentUid(...);
    *** getName(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.R
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.R$drawable {
    *** art_default;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.R$string {
    *** about_maple_normal;
    *** about_maple_overview_plain;
    *** action;
    *** appearance_intro_start;
    *** confirm;
    *** direct_install;
    *** download_cancel;
    *** group_contains_apps;
    *** home_click_to_install;
    *** home_environment_check_failed;
    *** home_safe_mode_banner_summary;
    *** home_safe_mode_banner_title;
    *** home_safe_mode_manage_modules;
    *** home_status_checking;
    *** home_working_version;
    *** install_advanced_title;
    *** install_image_analyzing;
    *** install_image_invalid;
    *** install_image_patch_failed;
    *** install_image_required;
    *** install_image_verified;
    *** install_lenovo_aosp;
    *** install_lenovo_select_image;
    *** install_lenovo_title;
    *** install_next;
    *** install_partition_read_failed;
    *** jailbreak_mode;
    *** lite_hidden_confirm_cancel;
    *** maple_market_changelog;
    *** maple_market_close_menu;
    *** maple_market_from_repository;
    *** maple_market_local_file;
    *** maple_market_no_results;
    *** maple_market_open_project;
    *** maple_market_title;
    *** maple_market_update;
    *** maplesu_jailbreak_close;
    *** maplesu_jailbreak_copy_logs;
    *** maplesu_jailbreak_dirtyfrag;
    *** maplesu_jailbreak_export_logs;
    *** maplesu_jailbreak_ghostlock;
    *** maplesu_jailbreak_logs_close;
    *** maplesu_jailbreak_logs_latest;
    *** maplesu_jailbreak_logs_title;
    *** maplesu_jailbreak_open_logs;
    *** maplesu_jailbreak_share_logs;
    *** maplesu_jailbreak_start;
    *** maplesu_jailbreak_target_normal;
    *** maplesu_jailbreak_target_ultra;
    *** maplesu_su_disabled_enable;
    *** module;
    *** module_archive_checked;
    *** module_archive_checking;
    *** module_archive_invalid;
    *** module_author;
    *** module_capability_in_place_update;
    *** module_capability_separator;
    *** module_capability_stop;
    *** module_capability_toggle;
    *** module_card_artwork;
    *** module_card_processing;
    *** module_card_running;
    *** module_card_unsupported_summary;
    *** module_install;
    *** module_install_cancelled;
    *** module_maplesu_not_supported;
    *** module_open_webui;
    *** module_runtime_running;
    *** module_runtime_unsupported;
    *** module_type_confirmation_required;
    *** module_type_ksu;
    *** module_update;
    *** module_version;
    *** module_webui_external_blocked;
    *** module_webui_retry;
    *** normal_hidden_activate_failed;
    *** normal_hidden_activating;
    *** normal_hidden_copy;
    *** normal_hidden_done_title;
    *** normal_hidden_link_title;
    *** normal_hidden_prepare;
    *** normal_hidden_preparing;
    *** normal_hidden_retry;
    *** normal_hidden_saved_confirm;
    *** normal_hidden_setup_title;
    *** normal_hidden_webui_stop;
    *** root_install_required;
    *** select_file;
    *** settings;
    *** superuser;
    *** theme_advanced_media;
    *** theme_apply;
    *** theme_author_all;
    *** theme_cancel;
    *** theme_catalog_custom;
    *** theme_catalog_source;
    *** theme_catalog_url;
    *** theme_confirm;
    *** theme_delete;
    *** theme_delete_title;
    *** theme_download;
    *** theme_download_builtin;
    *** theme_download_done;
    *** theme_download_mode;
    *** theme_download_mode_title;
    *** theme_filter_sort;
    *** theme_font_custom;
    *** theme_font_system;
    *** theme_home_large;
    *** theme_home_small;
    *** theme_home_three;
    *** theme_import_file;
    *** theme_layout_title;
    *** theme_library;
    *** theme_library_empty;
    *** theme_name;
    *** theme_package_export;
    *** theme_reset;
    *** theme_reset_comic;
    *** theme_reset_confirm;
    *** theme_reset_message;
    *** theme_reset_question;
    *** theme_sort_default;
    *** theme_sort_name;
    *** theme_sound;
    *** theme_source_all;
    *** theme_title_advanced;
    *** theme_title_image_gif;
    *** theme_title_text;
    *** ui_d_appearance_title;
    *** ui_d_comic_intro;
    *** ui_d_comic_plain_style_summary;
    *** ui_d_decoration_check;
    *** ui_d_decoration_comic;
    *** ui_d_env_hook_summary;
    *** ui_d_env_install_title;
    *** ui_d_env_state_not_installed;
    *** ui_d_env_state_running;
    *** ui_d_env_title;
    *** ui_d_translucent;
    *** ui_polish_link_open;
    *** uninstall;
    *** webui_control_confirm_title;
    *** webui_control_restart_hint;
    *** webui_control_retry;
    *** webui_control_stop;
    *** webui_control_stopped;
    *** webui_control_stopping;
    *** webui_loading;
    *** work_mode_images_boot;
    *** work_mode_images_boot_optional;
    *** work_mode_images_restore_init_boot;
    *** work_mode_images_title;
    *** work_mode_images_use_current;
    *** work_mode_intro_action;
    *** work_mode_intro_title;
    *** work_mode_lite;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.data.model.AppInfo {
    *** getLabel(...);
    *** getPackageInfo(...);
    *** getPackageName(...);
    *** getUid(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.data.model.Module {
    <init>(...);
    *** getAuthor(...);
    *** getCanToggle(...);
    *** getEnabled(...);
    *** getHasWebUi(...);
    *** getId(...);
    *** getName(...);
    *** getPropsApplied(...);
    *** getRemove(...);
    *** getUnsupported(...);
    *** getUpdate(...);
    *** getVersion(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.data.model.ModuleUpdateInfo {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.data.repository.ModuleRepositoryImpl {
    <init>(...);
    *** getModules-IoAF18A(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.data.repository.SettingsRepository
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.data.repository.SettingsRepositoryImpl {
    <init>(...);
    *** applyComicPreset(...);
    *** execKsudFeatureSave(...);
    *** getAdbRootPersistValue(...);
    *** getAdbRootStatus(...);
    *** getAdbRootValue(...);
    *** getAutoJailbreak(...);
    *** getCheckModuleUpdate(...);
    *** getCheckUpdate(...);
    *** getColorSpec(...);
    *** getColorStyle(...);
    *** getEnableBlur(...);
    *** getEnableFloatingBottomBar(...);
    *** getEnableFloatingBottomBarBlur(...);
    *** getEnableNavigationBadge(...);
    *** getEnablePredictiveBack(...);
    *** getEnableSwipeDismiss(...);
    *** getEnableWebDebugging(...);
    *** getFlatHome(...);
    *** getGravityEnabled(...);
    *** getGravityMode(...);
    *** getHomeLargeCard(...);
    *** getIntentToken(...);
    *** getKernelUmountStatus(...);
    *** getKeyColor(...);
    *** getMiuixMonet(...);
    *** getModuleDescriptionMaxLines(...);
    *** getModuleRepoSortOrder(...);
    *** getModuleSortActionFirst(...);
    *** getModuleSortEnabledFirst(...);
    *** getNavIconCircle(...);
    *** getNavIconTint(...);
    *** getNavigationRailExpanded(...);
    *** getPageScale(...);
    *** getPagerInterceptionMode(...);
    *** getSelinuxHideStatus(...);
    *** getShellSuAdbOnlyPersistValue(...);
    *** getShellSuAdbOnlyStatus(...);
    *** getShellSuAdbOnlyValue(...);
    *** getSnowEnabled(...);
    *** getSnowSize(...);
    *** getSnowSpeed(...);
    *** getStatusDecorationAnchor(...);
    *** getStatusDecorationBiasX(...);
    *** getStatusDecorationBiasY(...);
    *** getStatusDecorationDim(...);
    *** getStatusDecorationHideText(...);
    *** getStatusDecorationZoom(...);
    *** getStealthCode(...);
    *** getSuCompatModePref(...);
    *** getSuCompatPersistValue(...);
    *** getSuCompatStatus(...);
    *** getSuLogFilters(...);
    *** getSulogPersistValue(...);
    *** getSulogStatus(...);
    *** getSulogValue(...);
    *** getSuperuserShowOnlyPrimaryUserApps(...);
    *** getSuperuserShowSystemApps(...);
    *** getSuperuserSortOption(...);
    *** getThemeMode(...);
    *** getTrollRainEnabled(...);
    *** getTrollRainSize(...);
    *** getTrollRainSpeed(...);
    *** getUiMode(...);
    *** getUiTranslucent(...);
    *** getUiTranslucentAlpha(...);
    *** getUiTranslucentChosen(...);
    *** getUseSoftReboot(...);
    *** getWallpaperBlur(...);
    *** getWallpaperDim(...);
    *** getWallpaperKind(...);
    *** getWallpaperLandKind(...);
    *** getWallpaperLandPath(...);
    *** getWallpaperPath(...);
    *** getWallpaperSeed(...);
    *** getWallpaperSeedColor(...);
    *** getWebAdminEnabled(...);
    *** isDefaultUmountModules(...);
    *** isKernelUmountEnabled(...);
    *** isLkmMode(...);
    *** isSelinuxHideEnabled(...);
    *** isSuEnabled(...);
    *** readNormalSettings(...);
    *** setAdbRootEnabled(...);
    *** setAutoJailbreak(...);
    *** setCheckModuleUpdate(...);
    *** setCheckUpdate(...);
    *** setColorSpec(...);
    *** setColorStyle(...);
    *** setDefaultUmountModules(...);
    *** setEnableBlur(...);
    *** setEnableFloatingBottomBar(...);
    *** setEnableFloatingBottomBarBlur(...);
    *** setEnableNavigationBadge(...);
    *** setEnablePredictiveBack(...);
    *** setEnableSwipeDismiss(...);
    *** setEnableWebDebugging(...);
    *** setFlatHome(...);
    *** setGravityEnabled(...);
    *** setGravityMode(...);
    *** setHomeLargeCard(...);
    *** setKernelUmountEnabled(...);
    *** setKeyColor(...);
    *** setMiuixMonet(...);
    *** setModuleDescriptionMaxLines(...);
    *** setModuleRepoSortOrder(...);
    *** setModuleSortActionFirst(...);
    *** setModuleSortEnabledFirst(...);
    *** setNavIconCircle(...);
    *** setNavIconTint(...);
    *** setNavigationRailExpanded(...);
    *** setPageScale(...);
    *** setPagerInterceptionMode(...);
    *** setSelinuxHideEnabled(...);
    *** setShellSuAdbOnlyEnabled(...);
    *** setSnowEnabled(...);
    *** setSnowSize(...);
    *** setSnowSpeed(...);
    *** setStatusDecorationAnchor(...);
    *** setStatusDecorationBiasX(...);
    *** setStatusDecorationBiasY(...);
    *** setStatusDecorationDim(...);
    *** setStatusDecorationHideText(...);
    *** setStatusDecorationZoom(...);
    *** setStealthCode(...);
    *** setSuCompatModePref(...);
    *** setSuLogFilters(...);
    *** setSulogEnabled(...);
    *** setSuperuserShowOnlyPrimaryUserApps(...);
    *** setSuperuserShowSystemApps(...);
    *** setSuperuserSortOption(...);
    *** setThemeMode(...);
    *** setTrollRainEnabled(...);
    *** setTrollRainSize(...);
    *** setTrollRainSpeed(...);
    *** setUiMode(...);
    *** setUiTranslucent(...);
    *** setUiTranslucentAlpha(...);
    *** setUseSoftReboot(...);
    *** setWallpaperBlur(...);
    *** setWallpaperDim(...);
    *** setWallpaperKind(...);
    *** setWallpaperLandKind(...);
    *** setWallpaperLandPath(...);
    *** setWallpaperPath(...);
    *** setWallpaperSeed(...);
    *** setWallpaperSeedColor(...);
    *** setWebAdminEnabled(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.data.repository.SuperUserRepositoryImpl {
    <init>(...);
    *** getAppList-IoAF18A(...);
    *** refreshProfiles-gIAlu-s(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.FptContainer {
    *** INSTANCE;
    *** read(...);
    *** write(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.PreparedTheme {
    *** getMetadata(...);
    *** getPath(...);
    *** getWarnings(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.StoreTheme {
    <init>(...);
    *** copy$default(...);
    *** getDownload(...);
    *** getId(...);
    *** getName(...);
    *** getPreview(...);
    *** json$manager(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeAssetValidation {
    *** INSTANCE;
    *** validateSelected(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeBackgroundKt {
    *** ThemeBackground(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeBackgroundSelection {
    <init>(...);
    *** getActive(...);
    *** getImage(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeDocument {
    *** INSTANCE;
    *** getDestinations(...);
    *** getLayouts(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeHomeLayout {
    *** Companion;
    *** Small;
    *** Three;
    *** getEntries(...);
    *** getValue(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeHomeLayout$Companion {
    *** statusResource(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeMediaKt {
    *** ThemeMediaHost(...);
    *** ThemePhotoImage(...);
    *** rememberThemePhoto(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeMetadata {
    *** Companion;
    <init>(...);
    *** getName(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeMetadata$Companion {
    *** read(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeMusic {
    *** INSTANCE;
    *** getPlaying(...);
    *** getRequested(...);
    *** toggle(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemePhoto {
    *** $stable;
    *** getDrawable(...);
    *** getFile(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeRepository {
    <init>(...);
    *** apply(...);
    *** apply$default(...);
    *** discard(...);
    *** edit(...);
    *** export(...);
    *** homeLayout$manager(...);
    *** prepare$default(...);
    *** recover(...);
    *** reset(...);
    *** reset$default(...);
    *** setAsset(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeRuntime {
    *** INSTANCE;
    *** document(...);
    *** document$default(...);
    *** file(...);
    *** file$default(...);
    *** generation(...);
    *** generation$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeStartupKt {
    *** ThemeStartupHost(...);
    *** ThemeStartupResource(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeStore {
    <init>(...);
    *** delete(...);
    *** download(...);
    *** identity$manager(...);
    *** index(...);
    *** index$default(...);
    *** local(...);
    *** packageFile(...);
    *** paused(...);
    *** samePackage(...);
    *** source(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeStoreSource {
    *** Companion;
    *** getId(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeStoreSource$Companion {
    *** custom(...);
    *** getFina(...);
    *** getMaple(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeWallpaperFrame {
    <init>(...);
    *** getPhoto(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeWallpaperKt {
    *** getLocalThemeWallpaperFrame(...);
    *** getLocalThemeWallpaperPage(...);
    *** getLocalThemeWallpaperStyle(...);
    *** rememberThemeBackgroundSelection(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.theme.ThemeWallpaperStyle {
    <init>(...);
    *** getDim(...);
    *** getGroup(...);
    *** getOpacity(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.MainActivity
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.MainActivityKt {
    *** getLocalMainPagerState(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.UiMode {
    *** Material;
    *** Miuix;
    *** getEntries(...);
    *** getValue(...);
    *** values(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.UiModeKt {
    *** getLocalUiMode(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.component.T3DialogsKt {
    *** T3DisabledDialog(...);
    *** T3NoticeDialog(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.component.bottombar.BottomBarDestination {
    *** Home;
    *** SuperUser;
    *** getEntries(...);
    *** getLabel(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.component.bottombar.BottomBarKt {
    *** BottomBar(...);
    *** SideRail(...);
    *** rememberMainPagerState(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.component.bottombar.MainPagerState {
    *** getPagerState(...);
    *** getSelectedPage(...);
    *** isNavigating(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.component.bottombar.NavigationBadgeState {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.component.maple.SkinRequest {
    <init>(...);
    *** getMode(...);
    *** getScene(...);
    *** getSettings(...);
    *** getSystemDark(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.component.maple.SkinTransitionKt {
    *** SkinTransitionHost(...);
    *** getLocalSkinTransition(...);
    *** rememberSkinTransitionState(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.component.maple.SkinTransitionState {
    <init>(...);
    *** apply$manager(...);
    *** getAppliedMode(...);
    *** getAppliedScene(...);
    *** getAppliedSettings(...);
    *** getSnapshot$manager(...);
    *** isRunning(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.AppNavDisplayKt {
    *** AppNavDisplay(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.Navigator {
    *** $stable;
    *** current(...);
    *** getBackStack(...);
    *** pop(...);
    *** push(...);
    *** replaceAll(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.NavigatorKt {
    *** getLocalNavigator(...);
    *** rememberNavigator(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.Route {
    *** Companion;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.Route$Appearance {
    *** INSTANCE;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.Route$AppearanceImages {
    *** INSTANCE;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.Route$ColorPalette {
    *** INSTANCE;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.Route$Companion {
    *** serializer(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.Route$Home {
    *** INSTANCE;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.Route$Install {
    *** INSTANCE;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.Route$Main {
    *** INSTANCE;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.navigation3.Route$Settings {
    *** INSTANCE;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.flash.FlashIt
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.flash.FlashIt$FlashModules {
    *** $stable;
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.flash.FlashScreenKt {
    *** FlashScreen(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.About7kKt {
    *** getABOUT_CREDITS(...);
    *** getABOUT_ULTRA_POINTS(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.AboutCredit {
    *** getName(...);
    *** getRole(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.AboutMapleDialogMaterialKt {
    *** AboutMapleDialogMaterial(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.AboutMapleDialogMiuixKt {
    *** AboutMapleDialogMiuix(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.HomeActions {
    <init>(...);
    *** getOnFeedbackClick(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.HomeBetaBannerKt {
    *** HomeBetaBanner(...);
    *** openFeedbackInBrowser(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.HomeEntryFacts {
    *** toString(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.HomeMaterialKt {
    *** HomeManagerUpdateBannerMaterial(...);
    *** HomePagerMaterial-dsL6K2w(...);
    *** MaterialStatusCard(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.HomeMiuixKt {
    *** HomeManagerUpdateBannerMiuix(...);
    *** HomePagerMiuix-dsL6K2w(...);
    *** StatusCard(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.HomeSafeModeBannerKt {
    *** HomeSafeModeBanner(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.HomeThemeLayoutsKt {
    *** ThemeHomeStatus(...);
    *** getLocalPreparedStatusThemeArt(...);
    *** getLocalThemeHomeCounts(...);
    *** statusThemeArt(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.HomeUiState {
    <init>(...);
    *** copy$default(...);
    *** getCheckUpdateEnabled(...);
    *** getEnvironmentChecking(...);
    *** getEnvironmentFailure(...);
    *** getEnvironmentMode(...);
    *** getHiddenConfirming(...);
    *** getKsuVersion(...);
    *** getLitePending(...);
    *** getRootReadiness(...);
    *** getShowEnvironmentStatusCard(...);
    *** getShowInstalledStatus(...);
    *** getShowLiteChecking(...);
    *** getSystemInfo(...);
    *** isLateLoadMode(...);
    *** isRootAvailable(...);
    *** isSafeMode(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.StatusThemeArt {
    *** getPhoto(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.SystemInfo {
    <init>(...);
    *** getSelinuxStatus(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.home.ThemeHomeCounts {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.install.InstallAdvancedOptionsKt {
    *** InstallAdvancedOptions(...);
    *** LenovoSigningDialog(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.install.InstallMethod
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.install.InstallMethod$SelectFile {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.install.InstallScreenActions {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.install.InstallUiState {
    <init>(...);
    *** copy$default(...);
    *** getAdvancedExpanded(...);
    *** getLenovoSigning(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.maplemarket.MapleMarketComponentsKt {
    *** MarketPage(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.maplemarket.MapleMarketInstallMenuKt {
    *** MapleMarketInstallMenu(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.maplemarket.MapleMarketScreenKt {
    *** MarketDetail(...);
    *** MarketModuleList(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.maplemarket.MapleMarketUiState {
    *** $stable;
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.module.ModuleActions {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.module.ModuleConfirmRequest
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.module.ModuleConfirmRequest$Update
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.module.ModuleMaterialKt {
    *** ModulePagerMaterial-iHT-50w(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.module.ModuleMiuixKt {
    *** ModulePagerMiuix-iHT-50w(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.module.ModuleUiState {
    <init>(...);
    *** copy$default(...);
    *** getHasLoaded(...);
    *** getLoadError(...);
    *** getModuleList(...);
    *** getModules(...);
    *** getPending(...);
    *** getPendingModuleIds(...);
    *** getRemovalChanges$manager(...);
    *** getSearchResults(...);
    *** isRefreshing(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.AppearanceImagesScreenKt {
    *** AppearanceImagesScreen(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.AppearanceIntroScreenKt {
    *** AppearanceIntroScreen(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.AppearanceSettingsBodyKt {
    *** AppearanceSettingsBody(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.NormalHiddenDialogKt {
    *** NormalHiddenDialog(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.NormalHiddenFailure {
    *** ACTIVATE;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.NormalHiddenUi {
    *** $stable;
    <init>(...);
    *** copy$default(...);
    *** getLinkSaved(...);
    *** getPortText(...);
    *** withPort(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.SettingsScreenActions {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.SettingsUiState {
    <init>(...);
    *** getHiddenMasked(...);
    *** getLiteHiddenCard(...);
    *** getManagerValid(...);
    *** getModePolicy(...);
    *** getWorkModeEntry(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.ThemeComponentsKt {
    *** ThemePage(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.ThemeOperationState {
    *** getBusy(...);
    *** getError(...);
    *** getPrepared(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.ThemeOperations {
    <init>(...);
    *** edit(...);
    *** error(...);
    *** getState(...);
    *** prepare$default(...);
    *** reset$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.ThemeOperationsKt {
    *** ThemeOperationsHost(...);
    *** getLocalThemeOperations(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.ThemeResetPreferenceKt {
    *** ThemeResetPreference(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.ThemeSettingsScreensKt {
    *** ThemeFontScreen(...);
    *** ThemeLayoutScreen(...);
    *** ThemeMediaScreen(...);
    *** WallpaperScreen(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.ThemeStoreScreenKt {
    *** ThemeStoreScreen(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.ThemeStoreState {
    *** getCatalog(...);
    *** getItems(...);
    *** getLoading(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.ThemeStoreViewModel {
    *** $stable;
    <init>(...);
    *** getRepository(...);
    *** getState(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.settings.UltraEnvironmentPanel {
    <init>(...);
    *** getBusy(...);
    *** getHookEnvironment(...);
    *** getState(...);
    *** loadInitialState(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.superuser.GroupedApps {
    *** getApps(...);
    *** getPrimary(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.screen.superuser.SuperUserUiState {
    *** getBlockedReason(...);
    *** getError(...);
    *** getGroupedApps(...);
    *** getHasLoaded(...);
    *** getSuEnabled(...);
    *** isRefreshing(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.theme.AppSettings {
    *** $stable;
    <init>(...);
    *** copy$default(...);
    *** getColorMode(...);
    *** getWallpaperColor(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.theme.ColorMode {
    *** DARK;
    *** LIGHT;
    *** MONET_DARK;
    *** MONET_LIGHT;
    *** MONET_SYSTEM;
    *** SYSTEM;
    *** getValue(...);
    *** isDark(...);
    *** values(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.theme.SkinContentKt {
    *** rememberSkinContent(...);
    *** rememberSkinContentWithParameter(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.theme.ThemeController {
    *** INSTANCE;
    *** getAppSettings$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.theme.ThemeKt {
    *** KernelSUTheme(...);
    *** getLocalColorMode(...);
    *** getLocalEnableBlur(...);
    *** getLocalEnableFloatingBottomBar(...);
    *** getLocalEnableFloatingBottomBarBlur(...);
    *** getLocalFlatUi(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.AppearancePreferences {
    *** prefs(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.AppearancePrefsPolicy {
    *** INSTANCE;
    *** comicPreset(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.BlurExtKt {
    *** rememberBlurBackdrop(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.FlashOutcome {
    *** SAVED_ONLY;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.FlashResult {
    *** getCode(...);
    *** getErr(...);
    *** getOutcome(...);
    *** getOutputPath(...);
    *** getShowReboot(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.KsuCliKt {
    *** downloadBoot$default(...);
    *** flashModule$default(...);
    *** getRootShell$default(...);
    *** hasMagisk(...);
    *** installBoot$default(...);
    *** installUltraKernelUpdate(...);
    *** rootAvailable(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.LkmSelection
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.LkmSelection$KmiNone {
    *** INSTANCE;
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.LkmSelection$KmiString {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.LogEventKt {
    *** saveBugreport$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.ModuleConfirmation {
    *** getEvidence(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.NavIcons {
    *** INSTANCE;
    *** currentFile$default(...);
    *** getKeys(...);
    *** has(...);
    *** load$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.PreparedStatusDecoration {
    *** getBitmap(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.SELinuxCheckerKt {
    *** getSELinuxStatusRaw(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.StatusDecoration {
    *** INSTANCE;
    *** clear(...);
    *** file(...);
    *** load(...);
    *** save(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.StatusDecorationKt {
    *** getLocalPreparedStatusDecoration(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.SystemRebootKt {
    *** runRebootRootCommand(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.UltraImageBuild
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.UltraImageBuild$AlreadyPatched {
    *** getCandidate(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.UltraImageBuild$Candidate {
    *** getBootSha256(...);
    *** getImage(...);
    *** getKernelSha256(...);
    *** getPartition(...);
    *** getRelease(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.UltraImageBuild$Refused {
    *** getMessage(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.UltraKernelInstallKt {
    *** ultraImageBuildCandidate$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.WallpaperHostKt {
    *** WallpaperHost(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.WallpaperPrefs {
    *** INSTANCE;
    *** observe(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.WallpaperStoreKt {
    *** extractSeedColor(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.WebAdminCli {
    *** INSTANCE;
    *** setEnabled(...);
    *** url(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.util.module.LatestVersionInfo {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.viewmodel.HomeViewModel {
    *** getEntryFacts(...);
    *** getUiState(...);
    *** refresh(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.viewmodel.ModuleViewModel {
    <init>(...);
    *** fetchModuleList$default(...);
    *** getUiState(...);
    *** toggleModule(...);
    *** undoUninstallModule(...);
    *** uninstallModule(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.viewmodel.SettingsViewModel {
    *** getUiState(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.viewmodel.SuperUserViewModel {
    <init>(...);
    *** enableSu(...);
    *** getUiState(...);
    *** refreshSuState(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.webui.ModuleWebUIActivity
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.webui.ModuleWebViewClient {
    <init>(...);
    *** onPageStarted(...);
    *** onRenderProcessGone(...);
    *** shouldInterceptRequest(...);
    *** shouldOverrideUrlLoading(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.webui.WebUIActivity
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.webui.WebUIState {
    <init>(...);
    *** setModDir(...);
    *** setWebView(...);
}
-keep,allowobfuscation,includedescriptorclasses class com.maplesu.core.ui.webui.WebViewInterface {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.ghostlock.extract.GhostlockProfileGate
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.ghostlock.extract.GhostlockProfileGate$Reason
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.ghostlock.extract.OtaCopy {
    *** INSTANCE;
    *** lineFor(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.ghostlock.extract.OtaFailure {
    *** INVALID_URL;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.ghostlock.extract.OtaParseState
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.ghostlock.extract.OtaParseState$Failed {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.ghostlock.extract.OtaParseState$Parsing {
    *** INSTANCE;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.BootImageInspector {
    *** inspect(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.BootImageInspector$InvalidImageException {
    *** code;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.BootImageInspector$Report {
    *** dtbOffset;
    *** fileSize;
    *** headerVersion;
    *** kernelOffset;
    *** kernelSize;
    *** ramdiskOffset;
    *** ramdiskSize;
    *** recoveryDtboOffset;
    *** secondOffset;
    *** sha256;
    *** signatureSize;
    *** toJson(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.BootImageRebuilder
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.BootImageRebuilder$Report {
    *** image;
    *** kernelChanged;
    *** source;
    *** toJson(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.BootImageRoundTrip {
    *** sha256(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.KernelCompression {
    *** decode(...);
    *** encode(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.RamdiskInspector {
    *** inspect(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.RamdiskInspector$Entry {
    *** path;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.RamdiskInspector$Form
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.RamdiskInspector$Report {
    *** entries;
    *** malformed;
    *** form(...);
    *** requireKsuLkmForm(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.RamdiskInspector$Verdict {
    *** ok;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.UltraInitBootProducer {
    *** readDeploymentNamespace(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.image.UltraInitBootProducer$Artifact
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.installer.FileInstallTransaction {
    *** commit(...);
    *** directory(...);
    *** directorySyncSupported(...);
    *** open(...);
    *** prepare(...);
    *** restore(...);
    *** state(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.installer.FileInstallTransaction$Checkpoint
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.installer.FileInstallTransaction$DirectorySync
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.installer.FileInstallTransaction$Failure {
    *** code;
    *** targetMayBeModified;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.installer.FileInstallTransaction$State {
    *** RECOVERY_REQUIRED;
    *** RESTORED;
    *** VERIFIED;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.installer.WorkMode {
    *** KSU;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.BootCandidatePublisher {
    *** INSTANCE;
    *** publish(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ImageRepository {
    <init>(...);
    *** export(...);
    *** import(...);
    *** importAndVerifyInstalledBoot$manager(...);
    *** restore(...);
    *** verify(...);
    *** withRebuiltKernel$manager(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ImageResult {
    *** getReport(...);
    *** getRoundTripJson(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ModuleArchiveInspector {
    *** inspect(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ModuleArchiveInspector$Failure {
    *** code;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ModuleArchiveInspector$Feature {
    *** SCRIPT;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ModuleArchiveInspector$ModuleType {
    *** KERNEL_SU;
    *** MAPLESU;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ModuleArchiveInspector$Report {
    *** entryCount;
    *** features;
    *** id;
    *** nativeEntry;
    *** properties;
    *** sha256;
    *** skipMount;
    *** type;
    *** versionCode;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.compat.NativeModuleAbi
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.compat.StoredInputs {
    *** INSTANCE;
    *** control(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.compat.UltraStateStorage {
    *** INSTANCE;
    *** flash(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.BaselineCaptureProbe {
    *** getFirmware(...);
    *** getKernelRelease(...);
    *** getSizes(...);
    *** getSlot(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.BaselineControlPlaneEntry {
    *** getId(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.BaselineIdentity {
    *** INSTANCE;
    *** fromDevice(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.BaselineProvisionerKt {
    *** reusableBaselineEntry$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.BootPartitionIdentity {
    *** getDeviceNumber(...);
    *** getFilesystem(...);
    *** getInode(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.BootPartitionSnapshot {
    *** copy$default(...);
    *** getIdentity(...);
    *** getName(...);
    *** getPath(...);
    *** getResolvedPath(...);
    *** getSha256(...);
    *** getSize(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.IInstallerService {
    *** getBootInstall(...);
    *** openBootImage(...);
    *** readUltraIdentity(...);
    *** refreshUltraAuthorization(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallPartition {
    *** BOOT;
    *** INIT_BOOT;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallTargetMode {
    *** NORMAL;
    *** ULTRA;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallerBootTarget {
    *** getSlotSuffix(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallerModeGate {
    *** INSTANCE;
    *** refusal(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallerModeGate$WriteScope {
    *** getEntries(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallerPartitionReader {
    *** INSTANCE;
    *** observe(...);
    *** openBootImage(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallerPending {
    *** INSTANCE;
    *** isPendingSwitchState(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallerRootSession {
    *** Companion;
    *** close(...);
    *** discoverBootTarget(...);
    *** snapshotCurrentBoot(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallerRootSession$Companion {
    *** open$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallerRootSourceRegistry {
    *** INSTANCE;
    *** require(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallerServiceClient {
    *** INSTANCE;
    *** analyzeCurrentBoot(...);
    *** beginInstallation(...);
    *** beginInstallation$default(...);
    *** cancelInstallation(...);
    *** continueInstallation(...);
    *** decode$manager(...);
    *** installTargetOf(...);
    *** installationStatus(...);
    *** installationStatus$default(...);
    *** lastTaskId(...);
    *** observe$manager(...);
    *** observeBootChain(...);
    *** prepareBootInstall(...);
    *** rememberTask(...);
    *** restoreInstallation(...);
    *** verifyInstallation(...);
    *** withService$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.InstallerSnapshot {
    *** getFirmware(...);
    *** getKernelRelease(...);
    *** getPartitions(...);
    *** getSelinux(...);
    *** getSlotSuffix(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.KsuKernelPreservation {
    *** INSTANCE;
    *** check(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.KsuKernelPreservation$Verdict
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.KsuKernelPreservation$Verdict$Ok
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.LenovoSigningOptions {
    <init>(...);
    *** copy$default(...);
    *** getAosp(...);
    *** getEnabled(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.RootScriptBaselineBridge {
    <init>(...);
    *** probe(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.RootScriptBaselineCapture {
    <init>(...);
    *** probe(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.RootSource {
    *** KSU_NATIVE;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.RootSourcePolicy {
    *** INSTANCE;
    *** sourceFor(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.StockInstallFlow {
    *** INSTANCE;
    *** install$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.installer.StockInstallPreflight {
    *** INSTANCE;
    *** discoverTarget(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.CarrierProof {
    *** getAttemptId(...);
    *** getBootId(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.DirtyfragAttempt {
    *** hookEnvFile(...);
    *** hookEvidence(...);
    *** readBootId(...);
    *** readEnforcing(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.GhostlockProfileCodec {
    *** INSTANCE;
    *** toWire(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.GhostlockProfiles {
    *** from(...);
    *** packagedDocument(...);
    *** releases(...);
    *** resolve(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.GhostlockProfiles$Profile {
    *** assumed(...);
    *** assumedFrom(...);
    *** document(...);
    *** file(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.HookAttestServer {
    *** INSTANCE;
    *** ensureListening(...);
    *** read(...);
    *** socketFile(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.HookEnv {
    *** INSTANCE;
    *** permissionsOk(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.HookEvidence {
    *** INSTANCE;
    *** read(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.HookEvidence$Merged {
    *** getDaemon(...);
    *** getEnforcing(...);
    *** getFailures(...);
    *** getHelper(...);
    *** getOk(...);
    *** getOrigin(...);
    *** getRestoreState(...);
    *** getStage2(...);
    *** getToken(...);
    *** getUnloadErrno(...);
    *** getUnloadState(...);
    *** getVerdict(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.HookWorker {
    *** INSTANCE;
    *** attest$default(...);
    *** refusal$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.HookWorker$Attestation {
    *** getAttempt(...);
    *** getBootId(...);
    *** getDomain(...);
    *** getEnforcing(...);
    *** getNonce(...);
    *** getPeerPid(...);
    *** getPeerUid(...);
    *** getReplyAttempt(...);
    *** getReplyBootId(...);
    *** getReplyNonce(...);
    *** getReplyPid(...);
    *** getStarttime(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.IrImages {
    *** INSTANCE;
    *** inventory(...);
    *** stageForHook(...);
    *** stageHookModule(...);
    *** supports(...);
    *** unsupported(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.IrImages$Canary {
    *** getAsset(...);
    *** getBytes(...);
    *** getSha256(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.IrImages$CanaryHelper
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.IrImages$Inventory {
    *** getCanary(...);
    *** getCanaryHelper(...);
    *** getL3Module(...);
    *** getMode(...);
    *** getRootModule(...);
    *** isRootCanary(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.IrImages$Module {
    *** getBytes(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakAdmission {
    *** INSTANCE;
    *** assess$default(...);
    *** ghostlockReleases(...);
    *** verifyArtifacts(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakAssessment {
    *** getBlockers(...);
    *** getCanStart(...);
    *** getKmi(...);
    *** getSourceCommit(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakBlocker {
    *** GHOSTLOCK_EXACT_PROFILE_MISSING;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakController {
    *** INSTANCE;
    *** probe$default(...);
    *** run$default(...);
    *** status(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakController$Outcome {
    <init>(...);
    *** getAttemptId(...);
    *** getFailure(...);
    *** getMessage(...);
    *** getStage(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakController$RootProbe {
    *** getError(...);
    *** getExitCode(...);
    *** getOutput(...);
    *** getRootPid(...);
    *** getRootUid(...);
    *** getSucceeded(...);
    *** getTimedOut(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakDeviceProbe {
    *** INSTANCE;
    *** collect(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakDiagnostics {
    *** attemptDirectory(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakExecutor {
    *** INSTANCE;
    *** escalate(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakLogPanelKt {
    *** JailbreakLogPanel(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakLogReader
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakLogReader$Line {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakLogReader$Snapshot {
    *** $stable;
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakLogReader$Tone {
    *** STEP;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakMethod {
    *** DIRTYFRAG;
    *** GHOSTLOCK;
    *** getEntries(...);
    *** getWireName(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakRequest {
    <init>(...);
    *** getAttemptId(...);
    *** getBootId(...);
    *** getMethod(...);
    *** getOrigin(...);
    *** getRootBackend(...);
    *** getSoftReboot(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakSelectionDialogKt {
    *** JailbreakSelectionDialogContent(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakSelectionState {
    *** $stable;
    <init>(...);
    *** copy$default(...);
    *** getOtaState(...);
    *** getRootTarget(...);
    *** getRunning(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakStatus {
    *** Companion;
    *** getAttemptId(...);
    *** getBootId(...);
    *** getCarrier(...);
    *** getEnforcingAtLease(...);
    *** getFailure(...);
    *** getMessage(...);
    *** getMethod(...);
    *** getOwnerPid(...);
    *** getRestoreCheckreqprot(...);
    *** getRestoreEnforcing(...);
    *** getRestoreFailures(...);
    *** getRestoreOrigin(...);
    *** getRestorePolicy(...);
    *** getRestoreState(...);
    *** getRestoreUnloadErrno(...);
    *** getRootBackend(...);
    *** getStage(...);
    *** getSuGate(...);
    *** getSuToken(...);
    *** getTarget(...);
    *** getWorkerDomain(...);
    *** getWorkerPid(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakStatus$Companion {
    *** clear(...);
    *** getLIVE_CLAIM(...);
    *** getORDER(...);
    *** read(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakTarget {
    *** getFingerprint(...);
    *** getKernelRelease(...);
    *** getSecurityPatch(...);
    *** getSoc(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakTargets {
    *** verdict(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.JailbreakTargets$Verdict {
    *** VERIFIED_TARGET;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.ShizukuAccess
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.jailbreak.ShizukuAccess$State
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuBackends {
    *** INSTANCE;
    *** nativeLoadError(...);
    *** natives(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuBootPatchOptions {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuCandidateWorkspaces {
    *** INSTANCE;
    *** install(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuCommands {
    *** INSTANCE;
    *** bootInfo$default(...);
    *** daemonPath(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuInstallProducer {
    *** Companion;
    <init>(...);
    *** produceCandidate$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuInstallProducer$Companion {
    *** sha256(...);
    *** shellJoin(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuNativesBackend {
    *** getAvailable(...);
    *** getKernelUAPIVersion(...);
    *** getVersion(...);
    *** isLateLoadMode(...);
    *** isManager(...);
    *** isSuEnabled(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuProduceResult
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuProduceResult$Produced {
    *** getCandidate(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuProducedCandidate {
    *** getCandidate(...);
    *** getSha256(...);
    *** getSize(...);
    *** getSourceSha256(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuRootCommandRunner {
    *** run(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ksu.KsuShellResult {
    <init>(...);
    *** getCode(...);
    *** getErrText(...);
    *** getOut(...);
    *** getOutText(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.litehidden.NormalHiddenController {
    *** cancel(...);
    *** prepare(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.litehidden.NormalHiddenEnv {
    *** INSTANCE;
    *** create(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.litehidden.NormalHiddenResult {
    *** getCode(...);
    *** getOk(...);
    *** getRecoveryUrl(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.litehidden.ParasiteScripts {
    *** INSTANCE;
    *** daemonStop(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.litehidden.RecoveryIdentity {
    *** Companion;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.litehidden.RecoveryIdentity$Companion {
    *** of(...);
    *** parse(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.litehidden.RecoveryIdentityScripts {
    *** INSTANCE;
    *** read(...);
    *** write(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.liteweb.RecoveryWebUiState {
    *** RUNNING;
    *** STOPPED;
    *** UNKNOWN;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.market.MarketModule {
    *** $stable;
    <init>(...);
    *** getId(...);
    *** getName(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.JsonModuleListParser {
    *** INSTANCE;
    *** parse(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.LiteModuleBootEpoch {
    *** INSTANCE;
    *** read(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.LiteModuleBootService {
    *** Companion;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.LiteModuleBootService$Companion {
    *** request(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.LiteModuleRuntimeState {
    *** Companion;
    *** getRemovalIdentity(...);
    *** getRemoveRequestedBootId(...);
    *** pendingStages(...);
    *** save(...);
    *** withStage$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.LiteModuleRuntimeState$Companion {
    *** read(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.LiteScriptModuleBackend {
    *** Companion;
    <init>(...);
    *** abandonBootTask$manager(...);
    *** action(...);
    *** applySystemProperties(...);
    *** install(...);
    *** listJson(...);
    *** listJson$default(...);
    *** markRemove(...);
    *** prepareBootTasks$manager(...);
    *** runBootTask$manager(...);
    *** setEnabled(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.LiteScriptModuleBackend$BootTask {
    *** getCommon(...);
    *** getId(...);
    *** getStage(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.LiteScriptModuleBackend$Companion {
    *** getBootScripts(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.LiteScriptModuleBackend$InstallResult {
    *** getArchiveError(...);
    *** getCode(...);
    *** getCommitUnknown(...);
    *** getNeedsReboot(...);
    *** getRootCommitted(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ManagementModuleScriptRunner {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ModuleCatalog {
    <init>(...);
    *** commit(...);
    *** discard(...);
    *** get(...);
    *** list(...);
    *** setEnabled(...);
    *** stage(...);
    *** uninstall(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ModuleCatalog$Entry {
    *** archive;
    *** enabled;
    *** report;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ModuleCatalog$Failure {
    *** code;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ModuleCatalog$Staged {
    *** token;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ModuleOpsPort
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ModuleScriptOutput {
    <init>(...);
    *** getCode(...);
    *** getErr(...);
    *** getOut(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ModuleScriptRunner
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ModuleToolDeployment {
    *** INSTANCE;
    *** busyboxPath(...);
    *** policyPath(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ModuleTree {
    *** INSTANCE;
    *** standardPath(...);
    *** unlink(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ModuleType {
    *** KERNELSU;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.ModuleTypeEvidence {
    *** getType(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.RootExecModuleScriptRunner {
    *** INSTANCE;
    *** run(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.RootModuleTree {
    <init>(...);
    *** remove(...);
    *** snapshot(...);
    *** snapshot$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.RootModuleTree$Snapshot {
    *** getDirectories(...);
    *** getDirectory(...);
    *** getEnabled(...);
    *** getFiles(...);
    *** getIdentity(...);
    *** getLegacyTarget(...);
    *** getMetadata(...);
    *** getRemoved(...);
    *** getRevision(...);
    *** getZygisk(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.modules.UltraRootKeyStore {
    *** INSTANCE;
    *** allowEnvironmentInstall(...);
    *** clearLegacy(...);
    *** isSuspended(...);
    *** recordRemoval(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.patching.LiteBootPatcher {
    <init>(...);
    *** findVerifiedCandidate(...);
    *** patch$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.patching.LiteBootPatcher$AlreadyPatchedImageException
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.patching.LiteBootPatcher$Candidate {
    *** getImage(...);
    *** getKernelRelease(...);
    *** getOutputSha256(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.DirectRoot {
    *** INSTANCE;
    *** run(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.DirectRoot$Result {
    *** getCode(...);
    *** getOutput(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.HookRootExecutor {
    *** Companion;
    *** available(...);
    *** run(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.HookRootExecutor$Companion {
    *** production$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.HookSuCompatibility {
    *** INSTANCE;
    *** traditionalReady(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.HookSuKeeperService
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.INativeSuService {
    *** start(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.INativeSuService$Stub {
    *** asInterface(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.INativeSuSession {
    *** awaitExit(...);
    *** cancel(...);
    *** close(...);
    *** openTerminal(...);
    *** resize(...);
    *** signal(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.INativeSuSession$Stub {
    *** asInterface(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.IRootManagementService {
    *** grant(...);
    *** grantCount(...);
    *** hasGrant(...);
    *** isEnabled(...);
    *** revoke(...);
    *** setEnabled(...);
    *** start(...);
    *** status(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.IRootManagementService$Stub {
    *** asInterface(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.IRootRequestService {
    *** start(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.IRootRequestService$Stub {
    *** asInterface(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.IRootTask {
    *** awaitExit(...);
    *** close(...);
    *** openStderr(...);
    *** openStdin(...);
    *** openStdout(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.JailbreakGrantStore {
    *** INSTANCE;
    *** grant(...);
    *** hasGrant(...);
    *** isEnabled(...);
    *** revoke(...);
    *** setEnabled(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ManagerCallerVerifier {
    <init>(...);
    *** enforce(...);
    *** withCallingManager(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.MapleSuCommandExecutor {
    <init>(...);
    *** openShell$default(...);
    *** start$default(...);
    *** startUnprivileged$manager(...);
    *** startUnprivileged$manager$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.MapleSuRuntime {
    *** INSTANCE;
    *** openShell(...);
    *** openShell$default(...);
    *** refresh(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.MapleSuSupervisedProcess {
    *** Companion;
    *** cancel(...);
    *** closeControl(...);
    *** getProcess(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.MapleSuSupervisedProcess$Companion {
    *** start$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.NativeSessionProcess {
    *** Companion;
    *** destroyForcibly(...);
    *** exitValue(...);
    *** getControl(...);
    *** getErrorStream(...);
    *** getInputStream(...);
    *** getOutputStream(...);
    *** isAlive(...);
    *** waitFor(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.NativeSessionProcess$Companion {
    *** start(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.NativeSessionProcess$Control {
    *** close(...);
    *** getOutputStream(...);
    *** timeout(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.NativeSu {
    *** executeScript(...);
    *** executeScript$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.NativeSu$SuResult {
    *** getCode(...);
    *** getErr(...);
    *** getOut(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.NativeSuBrokerService
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.NativeSuDaemon {
    *** INSTANCE;
    *** current(...);
    *** endpointMissing$manager(...);
    *** ensure(...);
    *** management(...);
    *** management$default(...);
    *** prepareHook(...);
    *** reproveHookOutcome$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.NativeSuDaemonPolicy {
    *** INSTANCE;
    *** check(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.NativeSuDirectory {
    *** INSTANCE;
    *** prepare$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.NativeSuDiscovery {
    *** peek(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RootBackend {
    *** HOOK;
    *** KSU;
    *** NONE;
    *** ULTRA_ENV;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RootCapability {
    *** toString(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RootExec {
    *** INSTANCE;
    *** capability(...);
    *** execute(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RootExecResult {
    *** getBackend(...);
    *** getCode(...);
    *** getDetail(...);
    *** getOk(...);
    *** getOutput(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RootManagement {
    *** INSTANCE;
    *** openShell(...);
    *** openShell$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RootManagementProcess {
    *** Companion;
    *** close(...);
    *** exitValue(...);
    *** getErrorStream(...);
    *** getInputStream(...);
    *** getOutputStream(...);
    *** waitFor(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RootManagementProcess$Companion {
    *** start$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RootRequestClient {
    *** Companion;
    *** start(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RootRequestClient$Companion {
    *** connect$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RootRequestClient$Session {
    *** awaitExit(...);
    *** cancel(...);
    *** getStderr(...);
    *** getStdin(...);
    *** getStdout(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RootRequestService
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RuntimeDeployment {
    *** INSTANCE;
    *** clientPath(...);
    *** deploy$manager$default(...);
    *** start$default(...);
    *** status(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.RuntimeDeployment$Status {
    *** getBrokerReady(...);
    *** getClientPath(...);
    *** getError(...);
    *** getStandardPath(...);
    *** toString(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SdkRootShellService
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.StageMark {
    *** INSTANCE;
    *** read(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.State {
    *** WIRED;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuAutoWire {
    *** INSTANCE;
    *** reconcile(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuDelivery {
    *** INSTANCE;
    *** deliver(...);
    *** deliveredPath(...);
    *** imagePresent(...);
    *** withdraw(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuDelivery$Result {
    *** getDetail(...);
    *** getOk(...);
    *** getPath(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuDeliveryKt {
    *** stateFor$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuEntryMode {
    *** INSTANCE;
    *** describe(...);
    *** read(...);
    *** write(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuEntryMode$Mode {
    *** COOPERATIVE_MINIMAL;
    *** TRADITIONAL_COMPAT;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuGate {
    *** read(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuGate$Gate {
    <init>(...);
    *** getArmed(...);
    *** getAttempt(...);
    *** getBootId(...);
    *** getBound(...);
    *** getState(...);
    *** getSuGate(...);
    *** getSuToken(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuGate$Token {
    <init>(...);
    *** getHex(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuPathWiring {
    *** INSTANCE;
    *** hasNoNativeLibraries(...);
    *** injectPath(...);
    *** injectPath$default(...);
    *** runningCommandLineFor$manager(...);
    *** wire(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuPathWiring$Result {
    *** getCode(...);
    *** getDetail(...);
    *** getOk(...);
    *** getReason(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.SuPublication {
    *** INSTANCE;
    *** publish(...);
    *** suDirectory(...);
    *** suPath(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.TrustedRuntime
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.TrustedRuntime$Origin
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.AndroidApplicationIdentities {
    <init>(...);
    *** read(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.ApplicationIdentity {
    *** apkPath;
    *** firstInstallTime;
    *** lastUpdateTime;
    *** packageName;
    *** signers;
    *** uid;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.AuthorizationPolicy {
    <init>(...);
    *** authorizeAndStart(...);
    *** grant(...);
    *** hasCurrentGrant(...);
    *** isEnabled(...);
    *** revoke(...);
    *** setEnabled(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.AuthorizationPolicy$TaskStarter
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.AuthorizationPolicy$TrustedIdentityProvider
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.AuthorizationSnapshot {
    *** appliedOf(...);
    *** getEnabled(...);
    *** getGrants(...);
    *** getRev(...);
    *** pendingApply(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.DirectPolicy {
    *** INSTANCE;
    *** grant(...);
    *** grantCount(...);
    *** hasGrant(...);
    *** hasGrantStrict(...);
    *** isEnabled(...);
    *** revoke(...);
    *** setEnabled(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.ManagerAuthorizations {
    *** INSTANCE;
    *** policy(...);
    *** profile$default(...);
    *** saveProfile(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.ManagerAuthorizations$RemotePolicy {
    *** currentGrantCount(...);
    *** grant(...);
    *** hasCurrentGrant(...);
    *** isEnabled(...);
    *** revoke(...);
    *** setEnabled(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.SuperuserApplied {
    *** getOk(...);
    *** getRev(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.SuperuserControl {
    *** grant(...);
    *** revoke(...);
    *** setEnabled(...);
    *** snapshot(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.authorization.SuperuserControl$Result {
    *** getCode(...);
    *** getDetail(...);
    *** getOk(...);
    *** getPendingApply(...);
    *** getRev(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.InstalledIdentity {
    *** getHelperPath(...);
    *** getHelperSha256(...);
    *** getHelperSize(...);
    *** getSource(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.NativeReceipt {
    *** Companion;
    *** array(...);
    *** getData(...);
    *** getReason(...);
    *** getSdkError(...);
    *** getSdkErrorCode(...);
    *** getSucceeded(...);
    *** string(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.NativeReceipt$Companion {
    *** bridgeFailure(...);
    *** parse(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.Source {
    *** Readback;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.SuExecResult {
    *** getExitCode(...);
    *** getOutput(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.SuUidProfile {
    *** getUid(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraAuthorization {
    *** INSTANCE;
    *** grantedPackages(...);
    *** isGranted(...);
    *** refresh(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraEnvFacts {
    *** getInstalledHelperSha256(...);
    *** getObservationScope(...);
    *** getQuiescing(...);
    *** getReadUnknown(...);
    *** getRunning(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraEnvironmentInstaller {
    *** INSTANCE;
    *** install$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraEnvironmentInstaller$Intent
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraEnvironmentInstaller$Outcome {
    *** getOk(...);
    *** getSummary(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe {
    *** INSTANCE;
    *** currentSnapshot(...);
    *** currentSnapshot$manager(...);
    *** refresh$default(...);
    *** usesUltraBackend(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe$Snapshot {
    *** getManagerOwnerIdentity(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe$State {
    *** NotInstalled;
    *** Running;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraEnvironmentUninstaller {
    *** INSTANCE;
    *** uninstall(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraEnvironmentUninstaller$Outcome {
    *** getOk(...);
    *** toString(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraEnvironmentUpgrade {
    *** INSTANCE;
    *** observationScope(...);
    *** readFacts$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraIdentityRecovery {
    *** INSTANCE;
    *** recover(...);
    *** uid$manager(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraInstallTransactions {
    *** INSTANCE;
    *** fingerprint(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraInstalledIdentityProbe {
    *** INSTANCE;
    *** installedHelperPath(...);
    *** invalidate(...);
    *** read(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraOutcome {
    *** CONDITION_UNMET;
    *** PENDING_REBOOT;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraPhase {
    *** HOST_ACCEPTED;
    *** PREPARED;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraReceipt {
    *** Companion;
    *** getOutcome(...);
    *** getSdkError(...);
    *** getUnknowns(...);
    *** toJson(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraReceipt$Companion {
    *** of$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraRuntimeClient {
    <init>(...);
    *** addSuAuth(...);
    *** decodeText(...);
    *** environmentState(...);
    *** moduleList(...);
    *** queryManagerOwnerIdentity(...);
    *** runRootCommand(...);
    *** suAuthSnapshot(...);
    *** suUidProfiles(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraRuntimeClient$ManagerOwnerIdentity {
    *** getEpoch(...);
    *** getFullUid(...);
    *** getHostInstance(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraRuntimeClient$ManagerOwnerIdentityResult {
    *** getIdentity(...);
    *** getReceipt(...);
    *** getSucceeded(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraRuntimeClient$SuAuthSnapshot {
    *** getCount(...);
    *** getOk(...);
    *** getPackages(...);
    *** getReceipt(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraRuntimeClient$SuUidProfilesSnapshot {
    *** getOk(...);
    *** getProfiles(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraSdkCredentials {
    *** INSTANCE;
    *** captureOwnerMarker(...);
    *** client(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.UltraSuChannel {
    <init>(...);
    *** getLastFailure(...);
    *** rootUid(...);
    *** runRoot$default(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.runtime.ultra.bridge.NativeBridge {
    *** getEnvironmentState(...);
    *** getInstalledEnvironmentVersion(...);
    *** getModuleList(...);
    *** getSdkVersion(...);
    *** getSystemStatusJson(...);
    *** installEnvironment(...);
    *** isAdbForcedDisabled(...);
    *** isLogEnabled(...);
    *** oneplusBypassIsWorkNormal(...);
    *** parseModuleDescriptor(...);
    *** queryManagerOwnerIdentity(...);
    *** testBasics(...);
    *** testRoot(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3AccountClient {
    *** Companion;
    <init>(...);
    *** getDisabledMessage(...);
    *** synchronize(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3AccountClient$Companion {
    *** isAccountMissing(...);
    *** isAlreadyRegistered(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3AccountStore
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3AndroidAccountStore {
    <init>(...);
    *** read(...);
    *** write(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3DeviceIdentity {
    *** INSTANCE;
    *** resolve(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3ForegroundController {
    <init>(...);
    *** start(...);
    *** stop(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3Operation {
    *** HEARTBEAT;
    *** LOGIN;
    *** NOTICE;
    *** REGISTER;
    *** UPDATE_CORE;
    *** values(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3Response {
    <init>(...);
    *** getCode(...);
    *** getMessage(...);
    *** getStatecode(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3Runtime {
    *** Companion;
    *** attach(...);
    *** background(...);
    *** destroy(...);
    *** foreground(...);
    *** getSecondsRemaining(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3Runtime$Companion {
    *** get(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3SdkTransport {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3Session {
    <init>(...);
    *** getAccount(...);
    *** getIdentity(...);
    *** getStatecode(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.t3.T3Transport {
    *** call(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelAssetSource
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelBuilder
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelBuilder$BaseInspection {
    *** getKnownStockBase(...);
    *** getPristine(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelBuilder$Candidate {
    *** getBaseBootSha256(...);
    *** getBootSha256(...);
    *** getExpectedPinned(...);
    *** getImage(...);
    *** getKernelRelease(...);
    *** getKernelSha256(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelContextAssets {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelDeploymentNamespace {
    *** Companion;
    *** name(...);
    *** record(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelDeploymentNamespace$Companion {
    *** decode(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelFlow {
    *** Companion;
    <init>(...);
    *** backupCurrentBoot(...);
    *** candidateForInstall(...);
    *** inspect(...);
    *** prepare(...);
    *** prepareTarget$default(...);
    *** releaseOf(...);
    *** selectForRelease(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelFlow$Companion {
    *** initBootArtifact(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelFlow$Preparation {
    *** getAlreadyInstalled(...);
    *** getCandidate(...);
    *** getVariant(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelOriginalRecovery {
    <init>(...);
    *** generatedKernelWithDeployment(...);
    *** recover(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelOriginalRecovery$Recovered {
    *** getDeploymentNamespace(...);
    *** getImage(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelOriginalRecovery$RecoveredKernel {
    *** getDeploymentNamespace(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelPatchAsset {
    *** getExpectedKernelSha256(...);
    *** getId(...);
    *** getKnownBaseImageBootSha256(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelPlan {
    *** Companion;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelPlan$Companion {
    *** readKernel(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelSelection {
    *** getFellBack(...);
    *** getPrimary(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelSpec {
    *** Companion;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.ultrakernel.UltraKernelSpec$Companion {
    *** releaseOf(...);
    *** sha256(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.Hashes {
    *** INSTANCE;
    *** sha256(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ModeRuntime {
    *** INSTANCE;
    *** awaitActivated(...);
    *** awaitPageMode(...);
    *** getActivation(...);
    *** getActive(...);
    *** getDetectionComplete(...);
    *** getDetectionFailure(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.WorkMode {
    *** Companion;
    *** KSU;
    *** LITE;
    *** getEntries(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.WorkMode$Companion {
    *** fromWire(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.boot.ModeBootstrap {
    *** INSTANCE;
    *** start(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.control.ControlJsonCodec {
    *** INSTANCE;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.control.ControlJsonKt {
    *** getControlJson(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.control.ControlPaths {
    *** INSTANCE;
    *** getROOT_FILE(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.detect.ProcBootIdSource {
    *** INSTANCE;
    *** read(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.detect.SuReadiness {
    *** USABLE;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.migration.MigrationPaths {
    *** INSTANCE;
    *** getKSU_MANAGER_PACKAGES(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.recovery.DiagnosticsExportResult {
    *** getBytes(...);
    *** getFile(...);
    *** getSha256(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.recovery.DiagnosticsExporter {
    <init>(...);
    *** export(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.recovery.DiagnosticsSnapshot {
    *** Companion;
    *** getNotes(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.recovery.DiagnosticsSnapshot$Companion {
    *** serializer(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.recovery.RootDiagnosticsReader {
    <init>(...);
    *** read(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.service.ClientPartition {
    *** getName(...);
    *** getObservedSha256(...);
    *** getSize(...);
    *** getState(...);
    *** getTargetSha256(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.service.ClientResult {
    *** getOk(...);
    *** getStatus(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.service.ClientStatus {
    *** getBootObserved(...);
    *** getError(...);
    *** getFromMode(...);
    *** getPartitions(...);
    *** getRebootRequired(...);
    *** getState(...);
    *** getSwitchId(...);
    *** getToMode(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.service.ModeSwitchFacade {
    *** abort(...);
    *** getOperationActive(...);
    *** reboot(...);
    *** retryVerify(...);
    *** rollback(...);
    *** startSwitch$default(...);
    *** statusFor(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.service.ModeSwitchRootSession {
    <init>(...);
    *** ensureReady(...);
    *** execute(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.service.ModeSwitchRuntime {
    *** INSTANCE;
    *** facadeOrNull(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.service.SwitchOutcome
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.service.SwitchOutcome$AwaitingReboot {
    *** getStatus(...);
    *** getSwitchId(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.service.SwitchOutcome$ImagesRequired {
    *** $stable;
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.service.SwitchOutcome$Verified
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.superuser.LiteImplantScripts {
    *** INSTANCE;
    *** quote(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.superuser.RootScriptResult {
    <init>(...);
    *** getCode(...);
    *** getOut(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.superuser.RootScriptRunner {
    *** run(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.switch.BaselineManifest {
    *** getPartitions(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.switch.BaselinePartition {
    *** getName(...);
    *** getSha256(...);
    *** getSize(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.switch.DeviceBootFacts {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.switch.HookResult {
    *** getCode(...);
    *** getOk(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.switch.ModeSwitchKmi {
    *** INSTANCE;
    *** parse(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ui.LiteHiddenCardUi {
    *** getShowsRow(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ui.LiteHiddenPageUi {
    <init>(...);
    *** copy$default(...);
    *** getConfirmStopWebUi(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ui.LiteHiddenScreenKt {
    *** WebUiServiceActions(...);
    *** WebUiStopDialog(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ui.SettingsModePolicy {
    *** getMode(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ui.WorkModeEntryPolicy {
    *** getShowSettingsRow(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ui.WorkModeImageDialogKt {
    *** WorkModeImageDialog(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ui.WorkModeSheetButtonStyle {
    *** DISABLED;
    *** LOADING;
    *** PRIMARY;
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ui.WorkModeSheetMaterialKt {
    *** WorkModeIntroDialogMaterial(...);
    *** WorkModeSheetMaterial(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ui.WorkModeSheetOption {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ui.WorkModeSheetUi {
    <init>(...);
}
-keep,allowobfuscation,includedescriptorclasses class dev.maplesu.manager.workmode.ui.WorkModeStrings {
    *** INSTANCE;
    *** modeNameRes(...);
    *** modeTaglineRes(...);
}
