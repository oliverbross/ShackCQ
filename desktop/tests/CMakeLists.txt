set(SHACKCQ_TEST_LIBS shackcq_desktop_services Qt6::Test)

qt_add_executable(desktop_data_contract_tests unit/desktop_data_contract_tests.cpp)
target_link_libraries(desktop_data_contract_tests PRIVATE ${SHACKCQ_TEST_LIBS})
target_compile_definitions(desktop_data_contract_tests PRIVATE
    SHACKCQ_SHARED_FIXTURES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../../fixtures/platform")
shackcq_target_warnings(desktop_data_contract_tests)
add_test(NAME desktop_data_contract_tests COMMAND desktop_data_contract_tests)

qt_add_executable(desktop_network_contract_tests integration/desktop_network_contract_tests.cpp)
target_link_libraries(desktop_network_contract_tests PRIVATE ${SHACKCQ_TEST_LIBS})
shackcq_target_warnings(desktop_network_contract_tests)
add_test(NAME desktop_network_contract_tests COMMAND desktop_network_contract_tests)

qt_add_executable(desktop_tci_contract_tests integration/desktop_tci_contract_tests.cpp)
target_link_libraries(desktop_tci_contract_tests PRIVATE ${SHACKCQ_TEST_LIBS} Qt6::WebSockets)
shackcq_target_warnings(desktop_tci_contract_tests)
add_test(NAME desktop_tci_contract_tests COMMAND desktop_tci_contract_tests)

qt_add_executable(remote_station_service_tests integration/remote_station_service_tests.cpp)
target_link_libraries(remote_station_service_tests PRIVATE ${SHACKCQ_TEST_LIBS} Qt6::WebSockets)
shackcq_target_warnings(remote_station_service_tests)
add_test(NAME remote_station_service_tests COMMAND remote_station_service_tests)

qt_add_executable(desktop_platform_safety_tests unit/desktop_platform_safety_tests.cpp)
target_link_libraries(desktop_platform_safety_tests PRIVATE ${SHACKCQ_TEST_LIBS})
shackcq_target_warnings(desktop_platform_safety_tests)
add_test(NAME desktop_platform_safety_tests COMMAND desktop_platform_safety_tests)

qt_add_executable(cloud_agent_client_tests unit/cloud_agent_client_tests.cpp)
target_link_libraries(cloud_agent_client_tests PRIVATE ${SHACKCQ_TEST_LIBS} Qt6::WebSockets)
target_compile_definitions(cloud_agent_client_tests PRIVATE
    SHACKCQ_RADIO_AGENT_FIXTURES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../../fixtures/radio-agent-v1")
shackcq_target_warnings(cloud_agent_client_tests)
add_test(NAME cloud_agent_client_tests COMMAND cloud_agent_client_tests)

qt_add_executable(desktop_rf_observation_tests unit/desktop_rf_observation_tests.cpp)
target_link_libraries(desktop_rf_observation_tests PRIVATE ${SHACKCQ_TEST_LIBS})
shackcq_target_warnings(desktop_rf_observation_tests)
add_test(NAME desktop_rf_observation_tests COMMAND desktop_rf_observation_tests)
set_tests_properties(desktop_rf_observation_tests PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen")

qt_add_executable(desktop_parity_contract_tests unit/desktop_parity_contract_tests.cpp)
target_link_libraries(desktop_parity_contract_tests PRIVATE ${SHACKCQ_TEST_LIBS})
shackcq_target_warnings(desktop_parity_contract_tests)
add_test(NAME desktop_parity_contract_tests COMMAND desktop_parity_contract_tests)

qt_add_executable(desktop_functional_owner_tests unit/desktop_functional_owner_tests.cpp)
target_link_libraries(desktop_functional_owner_tests PRIVATE ${SHACKCQ_TEST_LIBS})
shackcq_target_warnings(desktop_functional_owner_tests)
add_test(NAME desktop_functional_owner_tests COMMAND desktop_functional_owner_tests)

qt_add_executable(desktop_ui_contract_tests unit/desktop_ui_contract_tests.cpp)
target_link_libraries(desktop_ui_contract_tests PRIVATE ${SHACKCQ_TEST_LIBS})
target_compile_definitions(desktop_ui_contract_tests PRIVATE
    SHACKCQ_DESKTOP_QML_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../qml/ShackCQ"
    SHACKCQ_DESKTOP_ICON_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../resources/icons"
    SHACKCQ_DESKTOP_APP_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../src/app")
shackcq_target_warnings(desktop_ui_contract_tests)
add_test(NAME desktop_ui_contract_tests COMMAND desktop_ui_contract_tests)

qt_add_executable(desktop_scale_soak_tests integration/desktop_scale_soak_tests.cpp)
target_link_libraries(desktop_scale_soak_tests PRIVATE ${SHACKCQ_TEST_LIBS})
shackcq_target_warnings(desktop_scale_soak_tests)
add_test(NAME desktop_scale_soak_tests COMMAND desktop_scale_soak_tests)
set_tests_properties(desktop_scale_soak_tests PROPERTIES TIMEOUT 180)

add_test(NAME desktop_qml_tests
    COMMAND Qt6::qmltestrunner -input ${CMAKE_CURRENT_SOURCE_DIR}/qml
            -import ${CMAKE_CURRENT_SOURCE_DIR}/../qml/ShackCQ)
set_tests_properties(desktop_qml_tests PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen")
