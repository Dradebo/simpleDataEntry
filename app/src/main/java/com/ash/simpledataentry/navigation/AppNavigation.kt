package com.ash.simpledataentry.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ash.simpledataentry.domain.repository.DataEntryRepository
import com.ash.simpledataentry.navigation.Screen.DatasetInstanceScreen
//import com.ash.simpledataentry.navigation.Screen.CreateNewEntryScreen
//import com.ash.simpledataentry.navigation.Screen.DatasetInstanceScreen
import com.ash.simpledataentry.navigation.Screen.DatasetsScreen
//import com.ash.simpledataentry.navigation.Screen.EditEntryScreen
import com.ash.simpledataentry.navigation.Screen.LoginScreen
import com.ash.simpledataentry.presentation.about.AboutScreen
import com.ash.simpledataentry.presentation.dataEntry.CreateNewEntryScreen
import com.ash.simpledataentry.presentation.dataEntry.DataEntryViewModel
import com.ash.simpledataentry.presentation.dataEntry.EditEntryScreen
import com.ash.simpledataentry.presentation.datasetInstances.DatasetInstancesScreen
import com.ash.simpledataentry.presentation.datasets.DatasetsScreen
import com.ash.simpledataentry.presentation.issues.ReportIssuesScreen
import com.ash.simpledataentry.presentation.login.LoginScreen
import com.ash.simpledataentry.presentation.settings.SettingsScreen
import com.ash.simpledataentry.presentation.tracker.TrackerEnrollmentScreen
import com.ash.simpledataentry.presentation.tracker.EventCaptureScreen
import com.ash.simpledataentry.presentation.tracker.TrackerDashboardScreen

sealed class Screen(val route: String) {

    data object LoginScreen : Screen("login")  // Added login screen
    data object DatasetsScreen : Screen("datasets")
    data class DatasetInstanceScreen(val datasetId: String, val datasetName: String) : Screen("instances")
    data object SettingsScreen : Screen("settings")
    data object AboutScreen : Screen("about")
    data object ReportIssuesScreen : Screen("report_issues")
//    data object CreateNewEntryScreen : Screen("createnewinstance")
//    data object EditEntryScreen : Screen("editinstance")

}




@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = LoginScreen.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier
    ) {
        composable(LoginScreen.route) {
            LoginScreen(navController = navController)
        }
        composable(DatasetsScreen.route) {
            DatasetsScreen(navController = navController)
        }

        // Route for aggregate dataset instances (DATASET program type)
        composable(
            route = "DatasetInstances/{datasetId}/{datasetName}",
            arguments = listOf(
                navArgument("datasetId") { type = NavType.StringType },
                navArgument("datasetName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val datasetId = backStackEntry.arguments?.getString("datasetId") ?: ""
            val datasetName = backStackEntry.arguments?.getString("datasetName") ?: ""
            DatasetInstancesScreen(
                navController = navController,
                datasetId = datasetId,
                datasetName = datasetName
            )
        }

        // Route for tracker program enrollments (TRACKER program type) - CARD VIEW
        composable(
            route = "TrackerEnrollments/{programId}/{programName}",
            arguments = listOf(
                navArgument("programId") { type = NavType.StringType },
                navArgument("programName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val programId = backStackEntry.arguments?.getString("programId") ?: ""
            val programName = backStackEntry.arguments?.getString("programName") ?: ""
            com.ash.simpledataentry.presentation.tracker.TrackerEnrollmentsScreen(
                navController = navController,
                programId = programId,
                programName = programName
            )
        }

        // Route for event program instances (EVENT program type) - CARD VIEW
        composable(
            route = "EventInstances/{programId}/{programName}",
            arguments = listOf(
                navArgument("programId") { type = NavType.StringType },
                navArgument("programName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val programId = backStackEntry.arguments?.getString("programId") ?: ""
            val programName = backStackEntry.arguments?.getString("programName") ?: ""
            com.ash.simpledataentry.presentation.event.EventInstancesScreen(
                navController = navController,
                programId = programId,
                programName = programName
            )
        }



        composable(
            route = "CreateDataEntry/{datasetId}/{datasetName}",
            arguments = listOf(
                navArgument("datasetId") { type = NavType.StringType },
                navArgument("datasetName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val datasetId = backStackEntry.arguments?.getString("datasetId") ?: ""
            val datasetName = backStackEntry.arguments?.getString("datasetName") ?: ""
            val viewModel: DataEntryViewModel = hiltViewModel()



            CreateNewEntryScreen(
                navController = navController,
                datasetId = datasetId,
                datasetName = datasetName,
                viewModel = viewModel

            )
        }



        composable(
            route = "EditEntry/{datasetId}/{period}/{orgUnit}/{attributeOptionCombo}/{datasetName}?isNewEntry={isNewEntry}",
            arguments = listOf(
                navArgument("datasetId") { type = NavType.StringType },
                navArgument("period") { type = NavType.StringType },
                navArgument("orgUnit") { type = NavType.StringType },
                navArgument("attributeOptionCombo") { type = NavType.StringType },
                navArgument("datasetName") { type = NavType.StringType },
                navArgument("isNewEntry") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val datasetId = backStackEntry.arguments?.getString("datasetId") ?: ""
            val period = backStackEntry.arguments?.getString("period") ?: ""
            val orgUnit = backStackEntry.arguments?.getString("orgUnit") ?: ""
            val attributeOptionCombo = backStackEntry.arguments?.getString("attributeOptionCombo") ?: ""
            val datasetName = backStackEntry.arguments?.getString("datasetName") ?: ""
            val isNewEntry = backStackEntry.arguments?.getBoolean("isNewEntry") ?: false
            val viewModel: DataEntryViewModel = hiltViewModel()
            EditEntryScreen(
                viewModel = viewModel,
                navController = navController,
                datasetId = datasetId,
                datasetName = datasetName,
                period = period,
                orgUnit = orgUnit,
                attributeOptionCombo = attributeOptionCombo,
                isNewEntry = isNewEntry
            )
        }

        composable(Screen.SettingsScreen.route) {
            SettingsScreen(navController = navController)
        }

        composable(Screen.AboutScreen.route) {
            AboutScreen(navController = navController)
        }

        composable(Screen.ReportIssuesScreen.route) {
            ReportIssuesScreen(navController = navController)
        }

        // Tracker Navigation Routes

        // Route for tracker enrollment creation
        composable(
            route = "CreateEnrollment/{programId}/{programName}",
            arguments = listOf(
                navArgument("programId") { type = NavType.StringType },
                navArgument("programName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val programId = backStackEntry.arguments?.getString("programId") ?: ""
            val programName = backStackEntry.arguments?.getString("programName") ?: ""

            TrackerEnrollmentScreen(
                navController = navController,
                programId = programId,
                programName = programName,
                enrollmentId = null // Creating new enrollment
            )
        }

        // Route for tracker enrollment editing
        composable(
            route = "EditEnrollment/{programId}/{programName}/{enrollmentId}",
            arguments = listOf(
                navArgument("programId") { type = NavType.StringType },
                navArgument("programName") { type = NavType.StringType },
                navArgument("enrollmentId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val programId = backStackEntry.arguments?.getString("programId") ?: ""
            val programName = backStackEntry.arguments?.getString("programName") ?: ""
            val enrollmentId = backStackEntry.arguments?.getString("enrollmentId") ?: ""

            TrackerEnrollmentScreen(
                navController = navController,
                programId = programId,
                programName = programName,
                enrollmentId = enrollmentId
            )
        }

        // Route for event creation with program stage AND enrollment (TRACKER programs)
        composable(
            route = "CreateEvent/{programId}/{programName}/{programStageId}/{enrollmentId}",
            arguments = listOf(
                navArgument("programId") { type = NavType.StringType },
                navArgument("programName") { type = NavType.StringType },
                navArgument("programStageId") { type = NavType.StringType },
                navArgument("enrollmentId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val programId = backStackEntry.arguments?.getString("programId") ?: ""
            val programName = backStackEntry.arguments?.getString("programName") ?: ""
            val programStageId = backStackEntry.arguments?.getString("programStageId")
            val enrollmentId = backStackEntry.arguments?.getString("enrollmentId")

            EventCaptureScreen(
                navController = navController,
                programId = programId,
                programStageId = programStageId,
                eventId = null,
                enrollmentId = enrollmentId
            )
        }

        // Route for event creation with program stage only (EVENT programs without registration)
        composable(
            route = "CreateEvent/{programId}/{programName}/{programStageId}",
            arguments = listOf(
                navArgument("programId") { type = NavType.StringType },
                navArgument("programName") { type = NavType.StringType },
                navArgument("programStageId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val programId = backStackEntry.arguments?.getString("programId") ?: ""
            val programName = backStackEntry.arguments?.getString("programName") ?: ""
            val programStageId = backStackEntry.arguments?.getString("programStageId")

            EventCaptureScreen(
                navController = navController,
                programId = programId,
                programStageId = programStageId,
                eventId = null,
                enrollmentId = null
            )
        }

        // Route for event creation without program stage (will auto-detect)
        composable(
            route = "CreateEvent/{programId}/{programName}",
            arguments = listOf(
                navArgument("programId") { type = NavType.StringType },
                navArgument("programName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val programId = backStackEntry.arguments?.getString("programId") ?: ""
            val programName = backStackEntry.arguments?.getString("programName") ?: ""

            EventCaptureScreen(
                navController = navController,
                programId = programId,
                programStageId = null,
                eventId = null,
                enrollmentId = null
            )
        }

        // Route for event editing (from tracker - with enrollment)
        composable(
            route = "EditEvent/{programId}/{programName}/{eventId}/{enrollmentId}",
            arguments = listOf(
                navArgument("programId") { type = NavType.StringType },
                navArgument("programName") { type = NavType.StringType },
                navArgument("eventId") { type = NavType.StringType },
                navArgument("enrollmentId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val programId = backStackEntry.arguments?.getString("programId") ?: ""
            val programName = backStackEntry.arguments?.getString("programName") ?: ""
            val eventId = backStackEntry.arguments?.getString("eventId") ?: ""
            val enrollmentId = backStackEntry.arguments?.getString("enrollmentId")

            EventCaptureScreen(
                navController = navController,
                programId = programId,
                programStageId = null,
                eventId = eventId,
                enrollmentId = enrollmentId
            )
        }

        // Route for standalone event editing (no enrollment)
        composable(
            route = "EditStandaloneEvent/{programId}/{programName}/{eventId}",
            arguments = listOf(
                navArgument("programId") { type = NavType.StringType },
                navArgument("programName") { type = NavType.StringType },
                navArgument("eventId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val programId = backStackEntry.arguments?.getString("programId") ?: ""
            val programName = backStackEntry.arguments?.getString("programName") ?: ""
            val eventId = backStackEntry.arguments?.getString("eventId") ?: ""

            EventCaptureScreen(
                navController = navController,
                programId = programId,
                programStageId = null,
                eventId = eventId,
                enrollmentId = null
            )
        }

        // Route for tracker dashboard (enrollment details with events)
        composable(
            route = "TrackerDashboard/{enrollmentId}/{programId}/{programName}",
            arguments = listOf(
                navArgument("enrollmentId") { type = NavType.StringType },
                navArgument("programId") { type = NavType.StringType },
                navArgument("programName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val enrollmentId = backStackEntry.arguments?.getString("enrollmentId") ?: ""
            val programId = backStackEntry.arguments?.getString("programId") ?: ""
            val programName = backStackEntry.arguments?.getString("programName") ?: ""

            TrackerDashboardScreen(
                navController = navController,
                enrollmentId = enrollmentId,
                programId = programId,
                programName = programName
            )
        }
    }

    }
