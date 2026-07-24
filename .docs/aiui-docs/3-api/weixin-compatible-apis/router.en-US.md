# Router (router)

Provides APIs related to page routing and navigation.

## Navigation Methods

- `wx.switchTab(Object object)`: Navigates to a tabBar page and closes all other non-tabBar pages.
- `wx.reLaunch(Object object)`: Closes all pages and opens a specified page within the app.
- `wx.redirectTo(Object object)`: Closes the current page and navigates to a specified page within the app. Navigation to a tabBar page is not allowed.
- `wx.navigateTo(Object object)`: Keeps the current page open and navigates to a specified page within the app. Navigation to a tabBar page is not allowed.
- `wx.navigateBack(Object object)`: Closes the current page and returns to the previous page or multiple previous pages.
