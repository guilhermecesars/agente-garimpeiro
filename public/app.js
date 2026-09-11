document.addEventListener('DOMContentLoaded', () => {
    // --- Gate de acesso (cadastro / login / checkout simulado) ---
    const authGate = document.getElementById('authGate');
    const tabCadastro = document.getElementById('tabCadastro');
    const tabLogin = document.getElementById('tabLogin');
    const formAuth = document.getElementById('formAuth');
    const authEmail = document.getElementById('authEmail');
    const authSenha = document.getElementById('authSenha');
    const btnAuthSubmit = document.getElementById('btnAuthSubmit');
    const authErro = document.getElementById('authErro');
    const authStatus = document.getElementById('authStatus');

    let modoAuth = 'cadastro';

    function mostrarGate() {
        authGate.style.display = 'flex';
    }

    function esconderGate() {
        authGate.style.display = 'none';
    }

    function setModoAuth(modo) {
        modoAuth = modo;
        authErro.innerText = '';
        authStatus.innerText = '';
        if (modo === 'cadastro') {
            tabCadastro.classList.add('active');
            tabLogin.classList.remove('active');
            btnAuthSubmit.innerText = 'Criar conta e assinar (simulado)';
        } else {
            tabLogin.classList.add('active');
            tabCadastro.classList.remove('active');
            btnAuthSubmit.innerText = 'Entrar';
        }
    }

    tabCadastro.addEventListener('click', () => setModoAuth('cadastro'));
    tabLogin.addEventListener('click', () => setModoAuth('login'));

    async function chamarApi(caminho, dados) {
        const res = await fetch(caminho, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(dados)
        });
        const json = await res.json().catch(() => ({}));
        return { ok: res.ok, status: res.status, json };
    }

    async function finalizarComToken(token) {
        localStorage.setItem('garimpo_token', token);
        esconderGate();
        dispararNovaBusca();
    }

    formAuth.addEventListener('submit', async (e) => {
        e.preventDefault();
        authErro.innerText = '';
        authStatus.innerText = '';

        const email = authEmail.value.trim();
        const senha = authSenha.value;

        if (modoAuth === 'cadastro') {
            const cadastro = await chamarApi('/api/cadastro', { email, senha });
            if (!cadastro.ok) {
                authErro.innerText = cadastro.json.error || 'Erro ao criar conta.';
                return;
            }
            authStatus.innerText = 'Conta criada! Processando pagamento simulado...';
            const checkout = await chamarApi('/api/checkout', { email, senha });
            if (!checkout.ok) {
                authErro.innerText = checkout.json.error || 'Erro ao processar pagamento.';
                return;
            }
            await finalizarComToken(checkout.json.token);
        } else {
            const login = await chamarApi('/api/login', { email, senha });
            if (!login.ok) {
                authErro.innerText = login.json.error || 'Email ou senha inválidos.';
                return;
            }
            if (!login.json.assinante) {
                authStatus.innerText = 'Login ok. Finalizando pagamento simulado...';
                const checkout = await chamarApi('/api/checkout', { email, senha });
                if (!checkout.ok) {
                    authErro.innerText = checkout.json.error || 'Erro ao processar pagamento.';
                    return;
                }
                await finalizarComToken(checkout.json.token);
            } else {
                // Assinante sem token local (ex: outro navegador) - novo checkout simulado reemite o token.
                const checkout = await chamarApi('/api/checkout', { email, senha });
                await finalizarComToken(checkout.json.token);
            }
        }
    });

    const searchInput = document.getElementById('searchInput');
    const selectVolume = document.getElementById('selectVolume');
    const inputMinPrice = document.getElementById('inputMinPrice');
    const inputMaxPrice = document.getElementById('inputMaxPrice');
    const selectSort = document.getElementById('selectSort');
    const btnSearch = document.getElementById('btnSearch');
    const grid = document.getElementById('grid');
    const loading = document.getElementById('loading');
    const pagination = document.getElementById('pagination');
    const btnPrev = document.getElementById('btnPrev');
    const btnNext = document.getElementById('btnNext');
    const pageIndicator = document.getElementById('pageIndicator');

    const COTACAO_DOLAR = 5.60;
    let paginaAtual = 1;
    const cacheTraducao = {};

    // Guarda o último resultado bruto vindo do servidor, para permitir
    // reordenar localmente (mudar "Ordenar") sem gastar nova requisição de API.
    let ultimosProdutos = null;

    // Função de cálculo fiscal (II + ICMS + Frete Est.) e estimativa de lucro no Brasil
    function calcularSimulacaoImportacao(precoUSD, cotacaoDolar = 5.60, freteUSD = 4.00, margemDesejadaPercent = 80) {
        const precoBRL = precoUSD * cotacaoDolar;
        const freteBRL = freteUSD * cotacaoDolar;
        const baseCalculoBRL = precoBRL + freteBRL;

        // Alíquota de Imposto de Importação (20% até $50, 60% acima)
        let aliqII = (precoUSD <= 50) ? 0.20 : 0.60;
        let impostoImportacao = baseCalculoBRL * aliqII;

        // Regra simplificada com isenção parcial / taxa fixa até $50
        if (precoUSD <= 50) {
            impostoImportacao = Math.max(0, (precoUSD - 20) * cotacaoDolar * aliqII);
        }

        // ICMS (~20% efetivo por dentro)
        const icms = (baseCalculoBRL + impostoImportacao) * 0.20;
        const totalImpostos = impostoImportacao + icms;

        // Custo Total Posto no Brasil (Landed Cost)
        const custoLanded = baseCalculoBRL + totalImpostos;

        // Preço de venda sugerido considerando margem desejada e ~18% de taxas de marketplace (Mercado Livre/Shopee)
        const precoVendaSugerido = (custoLanded * (1 + (margemDesejadaPercent / 100))) / 0.82;
        const lucroEstimado = precoVendaSugerido - custoLanded - (precoVendaSugerido * 0.18);

        return {
            precoBRL: precoBRL.toFixed(2),
            impostosBRL: totalImpostos.toFixed(2),
            custoLanded: custoLanded.toFixed(2),
            vendaSugerida: precoVendaSugerido.toFixed(2),
            lucroEstimado: lucroEstimado.toFixed(2)
        };
    }

    async function traduzirChines(texto) {
        if (!texto) return texto;
        if (cacheTraducao[texto]) return cacheTraducao[texto];

        try {
            const url = `https://translate.googleapis.com/translate_a/single?client=gtx&sl=zh-CN&tl=pt&dt=t&q=${encodeURIComponent(texto)}`;
            const res = await fetch(url);
            const data = await res.json();
            if (data && data[0]) {
                const traduzido = data[0].map(item => item[0]).join('');
                cacheTraducao[texto] = traduzido;
                return traduzido;
            }
        } catch (e) {
            console.warn('Falha na tradução:', e);
        }
        return texto;
    }

    function ordenarLocalmente(produtos, sort) {
        const copia = [...produtos];
        if (sort === 'price_asc') {
            copia.sort((a, b) => a.priceUSD - b.priceUSD);
        } else if (sort === 'price_desc') {
            copia.sort((a, b) => b.priceUSD - a.priceUSD);
        } else if (sort === 'sales_desc') {
            copia.sort((a, b) => b.vendasRaw - a.vendasRaw);
        }
        return copia;
    }

    function renderizarProdutos(produtosTraduzidos) {
        grid.innerHTML = '';
        produtosTraduzidos.forEach(prod => {
            const sim = calcularSimulacaoImportacao(prod.priceUSD, COTACAO_DOLAR);

            const card = document.createElement('div');
            card.className = 'card';
            card.innerHTML = `
                <img src="${prod.image}" alt="${prod.titlePt}" onerror="this.src='https://images.unsplash.com/photo-1526170375885-4d8ecf77b99f?w=400'">
                <div class="card-body">
                    <div class="card-info" style="color: #f59e0b; font-weight: bold;">🇨🇳 ${prod.origem} | ${prod.ranking}</div>
                    <div class="card-info" style="color: #10b981;">🔥 ${prod.vendasFormatted}</div>
                    <div class="card-title">${prod.titlePt}</div>
                    <div style="font-size: 11px; color: #64748b; margin-bottom: 8px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;" title="${prod.title}">
                        🇨🇳 ${prod.title}
                    </div>

                    <div class="price-container">
                        <div>
                            <div class="price-usd">$${prod.priceUSD.toFixed(2)}</div>
                            <div class="price-brl">≈ R$ ${sim.precoBRL}</div>
                        </div>
                    </div>

                    <!-- CALCULADORA DE MARGEM E IMPOSTOS -->
                    <div class="card-calc">
                        <div class="calc-header">
                            <span>📊 Viabilidade Brasil</span>
                            <span>USD = R$ ${COTACAO_DOLAR.toFixed(2)}</span>
                        </div>
                        <div class="calc-row">
                            <span>Impostos Est. (II + ICMS):</span>
                            <span>R$ ${sim.impostosBRL}</span>
                        </div>
                        <div class="calc-row">
                            <span>Custo Total no BR:</span>
                            <strong>R$ ${sim.custoLanded}</strong>
                        </div>
                        <div class="calc-row">
                            <span>Sugestão de Venda (ML/Shopee):</span>
                            <strong style="color: #3b82f6;">R$ ${sim.vendaSugerida}</strong>
                        </div>
                        <div class="calc-row highlight">
                            <span>Lucro Líquido Est. / Unidade:</span>
                            <span>+ R$ ${sim.lucroEstimado}</span>
                        </div>
                    </div>

                    <a href="${prod.url}" target="_blank" class="btn-buy">🛒 Ver no Taobao / 1688 ➔</a>
                </div>
            `;
            grid.appendChild(card);
        });

        pageIndicator.innerText = `Página ${paginaAtual}`;
        btnPrev.disabled = (paginaAtual <= 1);
        pagination.style.display = 'flex';
    }

    async function buscarRankingChina(categoria, minVendas, minPrice, maxPrice, sort, page = 1) {
        const userToken = localStorage.getItem('garimpo_token');

        if (!userToken) {
            mostrarGate();
            return;
        }

        loading.innerText = '⏳ Consultando base de dados do Taobao em Shanghai...';
        loading.style.display = 'block';
        grid.innerHTML = '';
        pagination.style.display = 'none';

        try {
            const queryParams = new URLSearchParams({
                q: categoria,
                minVendas: minVendas || 0,
                minPrice: minPrice || '',
                maxPrice: maxPrice || '',
                sort: sort || 'sales_desc',
                page: page
            });

            const res = await fetch(`/api/garimpar?${queryParams.toString()}`, {
                method: 'GET',
                headers: { 'Authorization': 'Bearer ' + userToken }
            });

            if (res.status === 401) {
                loading.style.display = 'none';
                localStorage.removeItem('garimpo_token');
                mostrarGate();
                return;
            }

            let produtos = await res.json();

            if (!produtos || produtos.length === 0) {
                loading.style.display = 'none';
                ultimosProdutos = null;
                grid.innerHTML = '<p style="grid-column: 1/-1; text-align: center; color: #94a3b8;">Nenhum produto encontrado com os filtros selecionados.</p>';
                return;
            }

            loading.innerText = '🌐 Traduzindo títulos e calculando viabilidade...';

            let produtosTraduzidos = await Promise.all(produtos.map(async (prod) => {
                const tituloPt = await traduzirChines(prod.title);
                return { ...prod, titlePt: tituloPt };
            }));

            // Guarda para permitir reordenar sem nova requisição de API
            ultimosProdutos = produtosTraduzidos;

            produtosTraduzidos = ordenarLocalmente(produtosTraduzidos, sort);

            loading.style.display = 'none';
            renderizarProdutos(produtosTraduzidos);

        } catch (err) {
            loading.style.display = 'none';
            grid.innerHTML = '<p style="grid-column: 1/-1; text-align: center; color: #ef4444;">Erro de conexão com o servidor Java.</p>';
        }
    }

    function dispararNovaBusca() {
        paginaAtual = 1;
        const cat = searchInput.value.trim() || 'eletrônicos';
        const minVol = selectVolume.value;
        const minP = inputMinPrice.value;
        const maxP = inputMaxPrice.value;
        const sort = selectSort.value;
        buscarRankingChina(cat, minVol, minP, maxP, sort, paginaAtual);
    }

    function navegarPagina(delta) {
        paginaAtual += delta;
        if (paginaAtual < 1) paginaAtual = 1;
        const cat = searchInput.value.trim() || 'eletrônicos';
        const minVol = selectVolume.value;
        const minP = inputMinPrice.value;
        const maxP = inputMaxPrice.value;
        const sort = selectSort.value;
        buscarRankingChina(cat, minVol, minP, maxP, sort, paginaAtual);
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }

    // ⚠️ MUDANÇA: só busca de novo no clique do botão ou Enter no campo de texto.
    // Trocar "Volume" ou "Ordenar" NÃO dispara mais requisição automática à API.
    btnSearch.addEventListener('click', dispararNovaBusca);

    searchInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') dispararNovaBusca();
    });

    // "Ordenar" reordena localmente os produtos já carregados (sem gastar requisição).
    // Se ainda não houver produtos carregados, dispara a primeira busca normalmente.
    selectSort.addEventListener('change', () => {
        if (ultimosProdutos) {
            const reordenado = ordenarLocalmente(ultimosProdutos, selectSort.value);
            renderizarProdutos(reordenado);
        } else {
            dispararNovaBusca();
        }
    });

    // "Volume" precisa de nova requisição (o filtro é aplicado no servidor),
    // então avisamos o usuário em vez de disparar automaticamente.
    selectVolume.addEventListener('change', () => {
        loading.style.display = 'none';
        const aviso = document.createElement('p');
        aviso.style.cssText = 'grid-column: 1/-1; text-align: center; color: #f59e0b; margin-bottom: 8px;';
        aviso.innerText = '⚠️ Filtro de volume alterado. Clique em "Refinar & Garimpar" para aplicar.';
        grid.prepend(aviso);
    });

    btnPrev.addEventListener('click', () => navegarPagina(-1));
    btnNext.addEventListener('click', () => navegarPagina(1));

    dispararNovaBusca();
});