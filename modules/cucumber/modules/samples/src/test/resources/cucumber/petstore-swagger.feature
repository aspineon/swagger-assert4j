Feature: Petstore API

  Scenario: Find pet by status
    Given the Swagger definition at http://petstore.swagger.io/v2/swagger.json
    When a request to findPetsByStatus is made
    And status is test
    And the request expects json
    Then a 200 response is returned within 2000ms

#  Scenario: Create pet with parameters
#    Given the Swagger definition at http://petstore.swagger.io/v2/swagger.json
#    When a request to addPet is made
#    And name is doggies
#    And status is available
#    Then a 200 response is returned within 2000ms

#  Scenario: Get pet by ID
#    Given the Swagger definition at http://petstore.swagger.io/v2/swagger.json
#    When a request to getPetById is made
#    And id is 12341
#    And the request expects json
#    Then a 404 response is returned within 2000ms
#    And the response type is json
#    And the response contains a Server header
#