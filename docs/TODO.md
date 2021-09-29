# Release planning

## Introduction
There are still many features planned for *roboquant*. For sure the lists on this page are also not complete and new features will pop up over time. Prioritization might shift based also based on community input and contribution.

But at least it gives insight into the current planning when to add certain features. And of course PRs are very welcome and might very well expedite certain features. 

See also the [contributing](CONTRIBUTING.md) page on how to submit a PR.
    
## Version 1.0 (release date end of 2021)
Version 1.0 is all about making sure that (back-)testing works great, and the most common use-cases are covered. Much of the foundation is already in place, and the work remaining is mainly focused around usability:

* [ ] Improve the documentation in source code
* [ ] Improve unit test coverage
* [ ] Better calculation of remaining cash when open orders
* [ ] Add order/trade visualizations for the Jupyter notebooks
* [ ] Improve CSV parsing with extra configurable options
* [ ] Improve error messages and warnings to be more helpful
* [ ] Add detailed tutorials on how to install and get started
* [ ] Add info on key design concepts behind the software, so it becomes easier to contribute
* [ ] Bring back Interactive Brokers integration. The code is already developed but due to license restrictions the TwsApi.jar file cannot be included with roboquant. So have to come up with alternative solution.

## Version 2.0 (release date to be determined)
This version is all about adding mature live trading capabilities. Although there is already integration available in earlier versions, that is just to validate the architectural concepts supports live trading. These broker integrations are neither reliable nor complete. So what still needs to be done:

* [ ] More complete integrations with brokers and crypto-exchanges
* [ ] Cleanup Machine Learning module (prototype already developed)
* [ ] Advanced policies, like auto re-balancing portfolios
* [ ] Better margin account simulation support
* [ ] More metrics, especially around alpha and beta calculations
* [ ] See how to best fit crypto trading with current account structure
* [ ] Support additional advanced order types
* [ ] Add more advanced slippage-, fee- and fill-models


## Version x.y
The topics mentioned here are just some ideas for the future releases:

* [ ] Add support for reading different types of data feeds besides price info
* [ ] More ready-to-use strategies out of the box
* [ ] More complex deep learning strategies, like LSTM neural networks
* [ ] Right now messages and formats support English format only, add L10N/I18N support
* [ ] Make video(s) that show the steps to develop and test your own strategies
* [ ] Come up with a way users can easily share strategies, code snippets and other best practices


