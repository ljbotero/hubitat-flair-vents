# Hubitat Driver for Flair Smart Vents

This app allows you to control [Flair Smart Vents](https://flair.co/) in [Hubitat](https://hubitat.com/).

## Installation 

To install:

1. Install the Flair vent driver code:
   - In Hubitat hub go to **Drivers Code** > **New Driver**  
   - Copy and paste the [hubitat-flair-vents-driver.groovy](hubitat-flair-vents-driver.groovy) contents
   - Click **Save**

2. Install the Flair app code:
   - In Hubitat hub go to **Apps Code** > **New App**
   - Copy and paste the [hubitat-flair-vents-app.groovy](hubitat-flair-vents-app.groovy) contents
   - Click **Save**
   - Click **Add User App** under the app listing
   
3. Get credentials from Flair:
   - Submit a request to Flair via [this form](https://forms.gle/VohiQjWNv9CAP2ASA)
   - Flair will provide you a Client ID and Client Secret
   - Enter these into the Hubitat Flair app

4. Discover devices:
   - In the Hubitat app, click **Discover Devices**
   - Your Flair vents will be added automatically

The vents can now be controlled through the Hubitat dashboard.

## Usage

Each vent will appear as a separate device in Hubitat. 

You can control the vents by setting the **setLevel** attribute:

- `setLevel 0` - Close vent
- `setLevel 50` - Set vent to 50% open  
- `setLevel 100` - Fully open vent

The opening percentage will be reflected in the **level** attribute.

This allows automating vent open/close based on routines, presence, or other triggers.

![Flair Vent Device](hubitat-flair-vents-device.png)
