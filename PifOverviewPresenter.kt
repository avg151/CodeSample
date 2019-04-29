
@InjectViewState
class PifOverviewPresenter(
        private val repository: Repository,
        private val schedulers: Schedulers,
        private val logger: Logger,
        private val pif: Pif,
        private val defaultDynamicDateRange: DateRange
) : RxMvpPresenter<PifOverviewView>() {

    private val pifInvestmentToolsInteractor = PifInvestmentToolsInteractor(repository, schedulers, logger, pif)
    private val piePriceChartInteractor = PiePriceChartInteractor(repository, schedulers, logger, pif, defaultDynamicDateRange)
    private val pifManagerCommentInteractor = PifManagerCommentInteractor(repository, schedulers, logger, pif.externalId().toString())
    private val investProfileRedirectInteractor = InvestProfileRedirectInteractor(repository, schedulers, logger)

    override fun onFirstViewAttach() {
        super.onFirstViewAttach()

        val rawNameSingle = Single.just(pif)
                .filter { pif -> !pif.fullName().isNullOrEmpty() }
                .map { pif -> R.string.fragment_pif_overview_title_format.toStringResArgs(pif.fullName()) }
                .switchIfEmpty(Single.just(R.string.fragment_pif_overview_title.toStringResArgs()))
                .toFlowable()

        val rawBuyPriceFlowable = getPifPrice(pif, PifPriceType.BUY)
        val rawExchangePriceFlowable = getPifPrice(pif, PifPriceType.BUY_EXCHANGE)

        val rawHasGeneralAgreementsFlowable = hasGeneralAgreements()
        val rawPifFlowable = Flowable.just(pif)

        val investProfileRedirectTypeFlowable = investProfileRedirectInteractor.subscribe().unwrapResource()

        val headerFlowable = Flowables.combineLatest(rawNameSingle, rawBuyPriceFlowable,
                rawExchangePriceFlowable, rawHasGeneralAgreementsFlowable, rawPifFlowable, investProfileRedirectTypeFlowable,
                PifOverviewViewState::Header)
                .asResource()
                .observeOn(schedulers.computation)

        val pifOverviewInvestmentToolsFlowable = pifInvestmentToolsInteractor.subscribe()

        val piePriceChartFlowable = piePriceChartInteractor.subscribe()

        val portfolioMonthResultsFlowable = pifManagerCommentInteractor.subscribe()

        val itemsFlowable = Flowables.combineLatest(pifOverviewInvestmentToolsFlowable,
                piePriceChartFlowable, portfolioMonthResultsFlowable, PifOverviewFactory::create)
                .onErrorReturnItem(emptyList())

        Flowables.combineLatest(headerFlowable, itemsFlowable, ::PifOverviewViewState)
                .distinctUntilChanged()
                .observeOn(schedulers.mainThread)
                .autoDisposable()
                .subscribe(viewState::onViewStateChanged, RxThrowable.printStackTraceAndPropagate(logger))
    }

    private fun getPifPrice(pif: Pif, pifPriceType: PifPriceType): Flowable<Money> {
        val codeTicker = pif.codeTicker()
                ?: throw IllegalArgumentException("Code ticker should not be null")
        val regNumber = pif.regNumber()
                ?: throw IllegalArgumentException("Reg number should not be null")
        val filter = PifPriceFilter(codeTicker, regNumber, pifPriceType)
        return repository.getPifPriceWithCurrency(filter)
                .doOnError(RxThrowable.printStackTrace(logger))
                .unwrapResource()
    }

    private fun hasGeneralAgreements(): Flowable<Boolean> {
        val productTypes = listOf(ProductType.GENERAL_AGREEMENT_RUS)
        val productsFilter = ProductsFilter(productTypes, null, ProductStatus.OPEN)
        return repository.getProducts(productsFilter)
                .observeOn(schedulers.computation)
                .mapResource(PifOverviewFunctions.hasGeneralAgreements())
                .unwrapResource()
    }

    fun onDynamicDateRangeChanged(dateRange: DateRange) = piePriceChartInteractor.onDateRangeChanged(dateRange)

    fun onHideableButtonClick(hideable: Hideable) = pifInvestmentToolsInteractor.onHideableButtonClick(hideable)

    fun onQuizPassedChanged() = investProfileRedirectInteractor.onQuizPassedChanged()

}
