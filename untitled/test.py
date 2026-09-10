#!/usr/bin/env python
# -*- coding: utf-8 -*-

import pytest
from selenium import webdriver
from selenium.webdriver.common.by import By


class TestWebsite:
  # 1. Check browser configuration in browser_setup_and_teardown
  # 2. Run 'Selenium Tests' configuration
  # 3. Test report will be created in reports/ directory

  @pytest.fixture(autouse=True)
  def browser_setup_and_teardown(self):
    self.use_selenoid = False  # set to True to run tests with Selenoid

    if self.use_selenoid:
      self.browser = webdriver.Remote(
        command_executor='http://localhost:4444/wd/hub',
        desired_capabilities={
          "browserName": "chrome",
          "browserSize": "1920x1080"
        }
      )
    else:
      self.browser = webdriver.Chrome()

    self.browser.maximize_window()
    self.browser.implicitly_wait(10)
    self.browser.get("https://www.jetbrains.com/")

    yield

    self.browser.close()
    self.browser.quit()

  def test_tools_menu(self):
    """this test checks presence of Developer Tools menu item"""
    tools_menu = self.browser.find_element(By.XPATH,
                                           "//div[@data-test='main-menu-item' and @data-test-marker = 'Developer Tools']")

    tools_menu.click()

    menu_popup = self.browser.find_element(By.CSS_SELECTOR, "div[data-test='main-submenu']")
    assert menu_popup is not None

  def test_navigation_to_all_tools(self):
    """this test checks navigation by See All Tools button"""
    see_all_tools_button = self.browser.find_element(By.CSS_SELECTOR, "a.wt-button_mode_primary")
    see_all_tools_button.click()

    products_list = self.browser.find_element(By.ID, "products-page")
    assert products_list is not None
    assert self.browser.title == "All Developer Tools and Products by JetBrains"

  def test_search(self):
    """this test checks search from the main menu"""
    search_button = self.browser.find_element(By.CSS_SELECTOR, "[data-test='site-header-search-action']")
    search_button.click()

    search_field = self.browser.find_element(By.CSS_SELECTOR, "[data-test='search-input']")
    search_field.send_keys("Selenium")

    submit_button = self.browser.find_element(By.CSS_SELECTOR, "button[data-test='full-search-button']")
    submit_button.click()

    search_page_field = self.browser.find_element(By.CSS_SELECTOR, "input[data-test='search-input']")
    assert search_page_field.get_property("value") == "Selenium"
