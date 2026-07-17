package br.com.oficina.autenticacao.domain;

import br.com.oficina.autenticacao.persistence.UsuarioEntity;
import br.com.oficina.autenticacao.resource.dto.DashboardCredenciaisResponse;
import br.com.oficina.autenticacao.resource.dto.DashboardCredenciaisResponse.ContagemCredencial;
import br.com.oficina.autenticacao.resource.dto.DashboardCredenciaisResponse.CredencialAtencao;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@ApplicationScoped
public class DashboardCredenciaisService {
    private static final List<String> STATUS = List.of("NAO_ATIVADA", "ATIVACAO_PENDENTE", "ATIVA");
    private final AtivacaoCredencialService ativacaoCredencialService;

    public DashboardCredenciaisService(AtivacaoCredencialService ativacaoCredencialService) {
        this.ativacaoCredencialService = ativacaoCredencialService;
    }

    @Transactional
    public DashboardCredenciaisResponse consultar() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var credenciais = UsuarioEntity.<UsuarioEntity>listAll().stream()
                .filter(usuario -> usuario.externalId != null)
                .map(usuario -> new Credencial(usuario, ativacaoCredencialService.consultar(usuario.externalId)))
                .toList();
        var contagens = STATUS.stream()
                .map(status -> new ContagemCredencial(
                        status,
                        credenciais.stream().filter(item -> item.status().equals(status)).count()))
                .toList();
        var atencoes = credenciais.stream()
                .filter(item -> !item.status().equals("ATIVA"))
                .sorted(java.util.Comparator.comparing(Credencial::atualizadoEm))
                .limit(5)
                .map(item -> new CredencialAtencao(
                        item.usuario().externalId,
                        item.status(),
                        item.atualizadoEm(),
                        item.statusResponse().expiresAt(),
                        item.statusResponse().acoesPermitidas()))
                .toList();
        return new DashboardCredenciaisResponse(now, now, 30, contagens, atencoes);
    }

    private record Credencial(UsuarioEntity usuario, br.com.oficina.autenticacao.resource.dto.CredencialStatusResponse statusResponse) {
        String status() {
            return statusResponse.status();
        }

        OffsetDateTime atualizadoEm() {
            return usuario.lastEventAt == null ? OffsetDateTime.MIN : usuario.lastEventAt;
        }
    }
}
